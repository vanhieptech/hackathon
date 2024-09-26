package com.example;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SequenceDiagramGeneratorV1 {
    private static final Map<String, Project> projects = new ConcurrentHashMap<>();
    private static final Logger logger = Logger.getLogger(SequenceDiagramGeneratorV1.class.getName());
    private static final int THREAD_POOL_SIZE = Runtime.getRuntime().availableProcessors();

    static {
        try {
            FileHandler fileHandler = new FileHandler("sequence_diagram_generator.log");
            fileHandler.setFormatter(new SimpleFormatter());
            logger.addHandler(fileHandler);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        parseArguments(args);
        processProjects();
        generateSequenceDiagram();
    }

    private static void parseArguments(String[] args) {
        for (String arg : args) {
            if (arg.startsWith("--project=")) {
                String[] projectInfo = arg.substring("--project=".length()).split(",");
                if (projectInfo.length == 2) {
                    projects.put(projectInfo[0], new Project(projectInfo[0], projectInfo[1]));
                }
            }
        }
    }

    private static void processProjects() {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        List<Future<?>> futures = new ArrayList<>();

        logger.info("Processing " + projects.size() + " projects");
        for (Project project : projects.values()) {
            logger.info("Submitting project for processing: " + project.name);
            futures.add(executor.submit(() -> processProject(project)));
        }

        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException | ExecutionException e) {
                logger.severe("Error processing project: " + e.getMessage());
            }
        }

        executor.shutdown();
        logger.info("Finished processing all projects");
    }


    private static void processProject(Project project) {
        try {
            Files.walk(Paths.get(project.path))
                    .filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> processJavaFile(path.toFile(), project));
        } catch (IOException e) {
            logger.severe("Error processing project " + project.name + ": " + e.getMessage());
        }
    }

    private static void processJavaFile(File file, Project project) {
        try {
            CompilationUnit cu = StaticJavaParser.parse(file);
            cu.accept(new MethodCallVisitor(project), null);
        } catch (IOException e) {
            logger.warning("Error parsing file " + file.getPath() + ": " + e.getMessage());
        }
    }

    private static class MethodCallVisitor extends VoidVisitorAdapter<Void> {
        private final Project project;
        private String currentClass;

        public MethodCallVisitor(Project project) {
            this.project = project;
        }

        @Override
        public void visit(ClassOrInterfaceDeclaration n, Void arg) {
            currentClass = n.getNameAsString();
            super.visit(n, arg);
        }

        @Override
        public void visit(MethodDeclaration md, Void arg) {
            String methodSignature = currentClass + "." + md.getNameAsString();
            project.addMethod(methodSignature, md.getBody().map(Object::toString).orElse(""));

            md.findAll(MethodCallExpr.class).forEach(mce -> {
                String calledMethod = mce.getNameAsString();
                String calledClass = mce.getScope().map(Object::toString).orElse(currentClass);
                project.addMethodCall(methodSignature, new MethodCall(calledMethod, calledClass));
            });

            super.visit(md, arg);
        }
    }

    private static class Project {
        String path;
        String name;
        Map<String, String> methods = new ConcurrentHashMap<>();
        Map<String, List<MethodCall>> methodCalls = new ConcurrentHashMap<>();
        Set<String> exposedAPIs = ConcurrentHashMap.newKeySet();

        Project(String path, String name) {
            this.path = path;
            this.name = name;
        }

        void addMethod(String methodSignature, String methodBody) {
            methods.put(methodSignature, methodBody);
        }

        void addMethodCall(String caller, MethodCall callee) {
            methodCalls.computeIfAbsent(caller, k -> new CopyOnWriteArrayList<>()).add(callee);
        }
    }

    private static class MethodCall {
        String methodSignature;
        String className;

        MethodCall(String methodSignature, String className) {
            this.methodSignature = methodSignature;
            this.className = className;
        }
    }

    private static void generateSequenceDiagram() {
        StringBuilder diagramBuilder = new StringBuilder();
        diagramBuilder.append("sequenceDiagram\n");
        diagramBuilder.append("    autonumber\n");

        // Define participants
        Set<String> definedParticipants = new HashSet<>();
        Map<String, String> participantAliases = new HashMap<>();
        int aliasCounter = 0;

        for (Project project : projects.values()) {
            for (Map.Entry<String, List<MethodCall>> entry : project.methodCalls.entrySet()) {
                String caller = entry.getKey();
                defineParticipant(diagramBuilder, definedParticipants, participantAliases, caller, project.name, aliasCounter++);

                for (MethodCall callee : entry.getValue()) {
                    defineParticipant(diagramBuilder, definedParticipants, participantAliases, callee.className, project.name, aliasCounter++);
                }
            }
        }
        diagramBuilder.append("\n");

        // Generate sequence
        for (Project project : projects.values()) {
            diagramBuilder.append("    %% Project: ").append(project.name).append("\n");
            for (Map.Entry<String, List<MethodCall>> entry : project.methodCalls.entrySet()) {
                String caller = entry.getKey();
                List<MethodCall> callees = entry.getValue();

                for (MethodCall callee : callees) {
                    appendMethodCall(diagramBuilder, caller, callee, project.name, participantAliases);
                }
            }
            diagramBuilder.append("\n");
        }

        try (FileWriter writer = new FileWriter("sequence_diagram.mmd")) {
            writer.write(diagramBuilder.toString());
            logger.info("Sequence diagram generated: sequence_diagram.mmd");
        } catch (IOException e) {
            logger.severe("Error generating sequence diagram: " + e.getMessage());
        }
    }

    private static void defineParticipant(StringBuilder diagramBuilder, Set<String> definedParticipants,
                                          Map<String, String> participantAliases, String className,
                                          String projectName, int aliasCounter) {
        String participantName = sanitizeParticipantName(className);
        if (!definedParticipants.contains(participantName)) {
            String alias = "P" + aliasCounter;
            String stereotype = determineStereotype(className);
            diagramBuilder.append("    participant ").append(alias).append(" as ").append(participantName)
                    .append(" <<").append(stereotype).append(">>\n");
            definedParticipants.add(participantName);
            participantAliases.put(participantName, alias);
        }
    }

    private static void appendMethodCall(StringBuilder diagramBuilder, String caller, MethodCall callee,
                                         String projectName, Map<String, String> participantAliases) {
        String callerAlias = participantAliases.get(sanitizeParticipantName(caller));
        String calleeAlias = participantAliases.get(sanitizeParticipantName(callee.className));

        diagramBuilder.append("    ").append(callerAlias).append("->").append(calleeAlias)
                .append(": ").append(truncateMessage(getMethodName(callee.methodSignature))).append("\n");

        Project project = projects.get(projectName);
        if (project != null) {
            String methodBody = project.methods.get(callee.methodSignature);
            if (methodBody != null && !methodBody.isEmpty()) {
                appendMethodBodyDetails(diagramBuilder, methodBody, calleeAlias, projectName, participantAliases);
            }
        } else {
            logger.warning("Project not found: " + projectName);
        }

        diagramBuilder.append("    ").append(calleeAlias).append("-->").append(callerAlias).append(": return\n");
    }

    private static void appendMethodBodyDetails(StringBuilder diagramBuilder, String methodBody,
                                                String calleeAlias, String projectName,
                                                Map<String, String> participantAliases) {
        String[] lines = methodBody.split("\n");
        int nestingLevel = 0;
        Stack<String> controlStructureStack = new Stack<>();

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            // Handle control structures
            if (line.startsWith("if ") || line.startsWith("for ") || line.startsWith("while ")) {
                String controlStructure = line.split("\\s+")[0];
                controlStructureStack.push(controlStructure);
                diagramBuilder.append("    ").append(calleeAlias).append("->").append(calleeAlias).append(": ")
                        .append(truncateMessage(line)).append("\n");
                diagramBuilder.append("    activate ").append(calleeAlias).append("\n");
                if (controlStructure.equals("if")) {
                    diagramBuilder.append("    alt ").append(truncateMessage(line)).append("\n");
                } else {
                    diagramBuilder.append("    loop ").append(truncateMessage(line)).append("\n");
                }
                nestingLevel++;
            } else if (line.startsWith("else ") || line.startsWith("else{")) {
                diagramBuilder.append("    else ").append(truncateMessage(line)).append("\n");
            } else if (line.equals("}") && !controlStructureStack.isEmpty()) {
                nestingLevel--;
                String structure = controlStructureStack.pop();
                if (structure.equals("if") || structure.equals("else")) {
                    diagramBuilder.append("    end\n");
                } else {
                    diagramBuilder.append("    end\n");
                }
                diagramBuilder.append("    deactivate ").append(calleeAlias).append("\n");
            }

            // Handle method calls and other operations
            if (line.contains("repository.") || line.contains("Repository.")) {
                diagramBuilder.append("    ").append(calleeAlias).append("->Database: ")
                        .append(truncateMessage(line)).append("\n");
                diagramBuilder.append("    Database-->").append(calleeAlias).append(": return\n");
            } else if (line.contains("restTemplate.") || line.contains("webClient.")) {
                String apiName = extractApiName(line);
                diagramBuilder.append("    ").append(calleeAlias).append("->").append(apiName)
                        .append(": API Call\n");
                diagramBuilder.append("    ").append(apiName).append("-->").append(calleeAlias)
                        .append(": response\n");
            } else if (line.contains("new ") && line.contains("Service")) {
                String serviceName = extractServiceName(line);
                String serviceAlias = participantAliases.get(sanitizeParticipantName(serviceName));
                if (serviceAlias == null) {
                    serviceAlias = "S" + participantAliases.size();
                    participantAliases.put(sanitizeParticipantName(serviceName), serviceAlias);
                    diagramBuilder.append("    participant ").append(serviceAlias).append(" as ")
                            .append(sanitizeParticipantName(serviceName)).append(" <<Service>>\n");
                }
                diagramBuilder.append("    ").append(calleeAlias).append("->").append(serviceAlias)
                        .append(": create\n");
            } else if (line.contains("=")) {
                // Variable assignment
                diagramBuilder.append("    Note over ").append(calleeAlias).append(": ")
                        .append(truncateMessage(line)).append("\n");
            } else if (line.startsWith("return ")) {
                // Return statement
                diagramBuilder.append("    Note over ").append(calleeAlias).append(": Return: ")
                        .append(truncateMessage(line.substring(7))).append("\n");
            }
        }

        // Close any remaining control structures
        while (!controlStructureStack.isEmpty()) {
            controlStructureStack.pop();
            diagramBuilder.append("    end\n");
            diagramBuilder.append("    deactivate ").append(calleeAlias).append("\n");
        }
    }

    private static String truncateMessage(String message) {
        return message.length() > 50 ? message.substring(0, 47) + "..." : message;
    }

    private static String determineStereotype(String className) {
        if (className.endsWith("Controller")) return "Controller";
        if (className.endsWith("Service")) return "Service";
        if (className.endsWith("Repository")) return "Repository";
        if (className.endsWith("DTO")) return "DTO";
        return "Class";
    }

    private static String getColorForProject(String projectName) {
        return String.format("#%06x", (projectName.hashCode() & 0xffffff));
    }

    private static String extractApiName(String line) {
        if (line.contains("restTemplate")) return "REST_API";
        if (line.contains("webClient")) return "WebClient_API";
        return "External_API";
    }

    private static String extractServiceName(String line) {
        Pattern pattern = Pattern.compile("new\\s+(\\w+Service)");
        Matcher matcher = pattern.matcher(line);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "UnknownService";
    }

    private static String sanitizeParticipantName(String name) {
        return name.replaceAll("[^a-zA-Z0-9]", "_");
    }

    private static String getMethodName(String fullSignature) {
        return fullSignature.substring(fullSignature.lastIndexOf('.') + 1);
    }
}