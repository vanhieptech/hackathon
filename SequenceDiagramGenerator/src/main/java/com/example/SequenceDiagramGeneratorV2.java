package com.example;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class SequenceDiagramGeneratorV2 {
    private static final Map<String, Project> projects = new ConcurrentHashMap<>();
    private static final Logger logger = Logger.getLogger(SequenceDiagramGeneratorV2.class.getName());
    private static CombinedTypeSolver typeSolver;
    private static ParserConfiguration parserConfiguration;
    private static Map<String, String> participantTypes = new HashMap<>();
    private static Set<String> excludedClasses = new HashSet<>();
    private static Map<String, String> apiEndpoints = new HashMap<>();

    public static void main(String[] args) {
        parseArguments(args);
        loadConfig("sequence_diagram_config.txt");
        setupSymbolSolver();
        processProjects();
        generateSequenceDiagram();
    }

    private static void loadConfig(String configFile) {
        try (BufferedReader reader = new BufferedReader(new FileReader(configFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("participantType:")) {
                    String[] parts = line.substring("participantType:".length()).split("=");
                    participantTypes.put(parts[0].trim(), parts[1].trim());
                } else if (line.startsWith("exclude:")) {
                    excludedClasses.add(line.substring("exclude:".length()).trim());
                } else if (line.startsWith("apiEndpoint:")) {
                    String[] parts = line.substring("apiEndpoint:".length()).split("=");
                    apiEndpoints.put(parts[0].trim(), parts[1].trim());
                }
            }
        } catch (IOException e) {
            logger.warning("Error loading config file: " + e.getMessage());
        }
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

    private static void setupSymbolSolver() {
        typeSolver = new CombinedTypeSolver();
        typeSolver.add(new ReflectionTypeSolver(false));
        for (Project project : projects.values()) {
            typeSolver.add(new JavaParserTypeSolver(new File(project.path)));
        }
        typeSolver.add(new ReflectionTypeSolver());

        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(typeSolver);
        parserConfiguration = new ParserConfiguration().setSymbolResolver(symbolSolver);
    }

    private static void processProjects() {
        ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        List<Future<?>> futures = new ArrayList<>();

        for (Project project : projects.values()) {
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
            CompilationUnit cu = StaticJavaParser.parse(file, parserConfiguration.getCharacterEncoding());

            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cls -> {
                cls.getMethods().forEach(method -> {
                    String methodSignature = cls.getNameAsString() + "." + method.getSignature().asString();
                    project.addMethod(methodSignature, method);
                    if (isExposedAPI(method)) {
                        project.addExposedAPI(methodSignature);
                    }
                    processMethodCalls(project, cls.getNameAsString(), method);
                });
            });
        } catch (IOException e) {
            logger.warning("Error parsing file " + file.getPath() + ": " + e.getMessage());
        }
    }

    private static boolean isExposedAPI(MethodDeclaration method) {
        return method.getAnnotationByName("GetMapping").isPresent() ||
                method.getAnnotationByName("PostMapping").isPresent() ||
                method.getAnnotationByName("PutMapping").isPresent() ||
                method.getAnnotationByName("DeleteMapping").isPresent() ||
                method.getAnnotationByName("RequestMapping").isPresent();
    }

    private static void processMethodCalls(Project project, String className, MethodDeclaration method) {
        method.findAll(MethodCallExpr.class).forEach(methodCall -> {
            try {
                String callerSignature = className + "." + method.getSignature().asString();
                ResolvedMethodDeclaration resolvedMethod = methodCall.resolve();
                String calleeSignature = resolvedMethod.getQualifiedSignature();
                String calleeClassName = resolvedMethod.declaringType().getQualifiedName();

                List<String> parameterTypes = new ArrayList<>();
                for (int i = 0; i < resolvedMethod.getNumberOfParams(); i++) {
                    parameterTypes.add(resolvedMethod.getParam(i).describeType());
                }

                String returnType = resolvedMethod.getReturnType().describe();

                if (isExternalAPI(calleeClassName)) {
                    project.addMethodCall(callerSignature, new MethodCall(calleeSignature, "ExternalAPI", method.toString(),
                            parameterTypes, returnType));
                } else {
                    project.addMethodCall(callerSignature, new MethodCall(calleeSignature, calleeClassName, method.toString(),
                            parameterTypes, returnType));
                }
            } catch (UnsolvedSymbolException e) {
                logger.warning("Unsolved symbol in " + className + "." + method.getNameAsString() + ": " + methodCall);
                String callerSignature = className + "." + method.getSignature().asString();
                String calleeSignature = methodCall.getNameAsString() + "(...)";
                project.addMethodCall(callerSignature, new MethodCall(calleeSignature, "UnknownClass", method.toString(),
                        Collections.singletonList("Unknown"), "Unknown"));
            } catch (Exception e) {
                logger.warning("Failed to resolve method call in " + className + "." + method.getNameAsString() + ": " + methodCall);
                String callerSignature = className + "." + method.getSignature().asString();
                String calleeSignature = methodCall.getNameAsString() + "(...)";
                project.addMethodCall(callerSignature, new MethodCall(calleeSignature, "UnknownClass", method.toString(),
                        Collections.singletonList("Unknown"), "Unknown"));
            }
        });
    }

    private static void generateSequenceDiagram() {
        StringBuilder diagramBuilder = new StringBuilder();
        diagramBuilder.append("sequenceDiagram\n");
        diagramBuilder.append("    autonumber\n");

        // Define actors and participants
        diagramBuilder.append("    actor User\n");
        Set<String> definedParticipants = new HashSet<>();
        for (Project project : projects.values()) {
            for (String api : project.exposedAPIs) {
                String className = api.substring(0, api.lastIndexOf('.'));
                defineParticipant(definedParticipants, diagramBuilder, className);
            }
        }
        defineParticipant(definedParticipants, diagramBuilder, "Database");
        defineParticipant(definedParticipants, diagramBuilder, "ExternalAPI");

        // Generate sequence for exposed APIs
        for (Project project : projects.values()) {
            for (String api : project.exposedAPIs) {
                String className = api.substring(0, api.lastIndexOf('.'));
                String methodName = api.substring(api.lastIndexOf('.') + 1);
                diagramBuilder.append("    User->>+").append(className).append(": ").append(methodName).append("\n");
                generateMethodSequence(diagramBuilder, project, api, new HashSet<>(), 1);
                diagramBuilder.append("    ").append(className).append("-->>-User: return response\n");
            }
        }

        try (FileWriter writer = new FileWriter("sequence_diagram.mmd")) {
            writer.write(diagramBuilder.toString());
            logger.info("Sequence diagram generated: sequence_diagram.mmd");
        } catch (IOException e) {
            logger.severe("Error generating sequence diagram: " + e.getMessage());
        }
    }

    private static void generateMethodSequence(StringBuilder diagramBuilder, Project project, String methodSignature, Set<String> visitedMethods, int depth) {
        if (visitedMethods.contains(methodSignature) || depth > 10) return;
        visitedMethods.add(methodSignature);

        String className = methodSignature.substring(0, methodSignature.lastIndexOf('.'));
        List<MethodCall> methodCalls = project.methodCalls.get(methodSignature);
        if (methodCalls == null) return;

        for (MethodCall methodCall : methodCalls) {
            String calleeClass = methodCall.className;
            String calleeMethod = methodCall.methodSignature.substring(methodCall.methodSignature.lastIndexOf('.') + 1);

            if (calleeClass.endsWith("Repository")) {
                appendDatabaseCall(diagramBuilder, className, calleeMethod);
            } else if (calleeClass.contains("Client") || isExternalAPI(calleeClass)) {
                appendExternalAPICall(diagramBuilder, className, calleeClass, calleeMethod);
            } else {
                diagramBuilder.append("    ").append(className).append("->>+").append(calleeClass).append(": ").append(calleeMethod).append("\n");
                generateMethodSequence(diagramBuilder, project, methodCall.methodSignature, visitedMethods, depth + 1);
                appendReturnStatement(diagramBuilder, calleeClass, className, getReturnType(methodCall.methodSignature));
            }

            appendControlStructures(diagramBuilder, methodCall);
        }
    }

    private static boolean isExternalAPI(String className) {
        return className.contains("External")
                || className.contains("Client")
                || className.endsWith("ApiClient")
                || className.endsWith("ServiceClient")
                || apiEndpoints.containsKey(className);
    }

    private static String getReturnType(String methodSignature) {
        int lastParenIndex = methodSignature.lastIndexOf(')');
        if (lastParenIndex != -1 && lastParenIndex < methodSignature.length() - 1) {
            return methodSignature.substring(lastParenIndex + 1).trim();
        }
        return "void"; // Default to void if return type can't be determined
    }

    private static void appendDatabaseCall(StringBuilder diagramBuilder, String caller, String method) {
        diagramBuilder.append("    ").append(caller).append("->>Database: ").append(method).append("\n");
        diagramBuilder.append("    Database-->>").append(caller).append(": return result\n");
    }

    private static void appendExternalAPICall(StringBuilder diagramBuilder, String caller, String api, String method) {
        diagramBuilder.append("    ").append(caller).append("->>ExternalAPI: ").append(api).append(".").append(method).append("\n");
        diagramBuilder.append("    ExternalAPI-->>").append(caller).append(": return response\n");
    }

    private static void appendReturnStatement(StringBuilder diagramBuilder, String callee, String caller, String returnType) {
        diagramBuilder.append("    ").append(callee).append("-->>-").append(caller).append(": return ").append(returnType).append("\n");
    }

    private static void defineParticipant(Set<String> definedParticipants, StringBuilder diagramBuilder, String participant) {
        if (definedParticipants.add(participant)) {
            String type = getParticipantType(participant);
            diagramBuilder.append("    participant ").append(participant).append(" as ").append(participant)
                    .append(" <<").append(type).append(">>\n");
        }
    }

    private static void appendControlStructures(StringBuilder diagramBuilder, MethodCall methodCall) {
        if (methodCall.methodBody.contains("if ")) {
            diagramBuilder.append("    alt Condition met\n");
            diagramBuilder.append("        # Process when condition is true\n");
            diagramBuilder.append("    else Condition not met\n");
            diagramBuilder.append("        # Process when condition is false\n");
            diagramBuilder.append("    end\n");
        }
        if (methodCall.methodBody.contains("for ") || methodCall.methodBody.contains("while ")) {
            diagramBuilder.append("    loop For each item\n");
            diagramBuilder.append("        # Process loop body\n");
            diagramBuilder.append("    end\n");
        }
    }


    private static String getParticipantType(String participant) {
        for (Map.Entry<String, String> entry : participantTypes.entrySet()) {
            if (participant.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        if (participant.endsWith("Controller")) return "Controller";
        if (participant.endsWith("Service")) return "Service";
        if (participant.endsWith("Repository")) return "Repository";
        if (participant.contains("Client")) return "ExternalService";
        return "Component";
    }

    private static void generateMethodCalls(StringBuilder diagramBuilder, Map<String, List<String>> methodCalls, String participant) {
        if (methodCalls.containsKey(participant)) {
            for (String call : methodCalls.get(participant)) {
                String[] parts = call.split("->");
                String caller = parts[0];
                String[] calleeParts = parts[1].split(": ", 2);
                String callee = calleeParts[0];
                String method = calleeParts[1];

                String calleeType = getParticipantType(callee);
                switch (calleeType) {
                    case "Repository":
                        diagramBuilder.append("    ").append(caller).append("->Database: ").append(method).append("\n");
                        diagramBuilder.append("    Database-->").append(caller).append(": return\n");
                        break;
                    case "ExternalService":
                        String endpoint = apiEndpoints.getOrDefault(callee, "API Call");
                        diagramBuilder.append("    ").append(caller).append("->>").append(callee).append(": ").append(endpoint).append("\n");
                        diagramBuilder.append("    ").append(callee).append("-->").append(caller).append(": return\n");
                        break;
                    default:
                        diagramBuilder.append("    ").append(call).append("\n");
                        diagramBuilder.append("    ").append(callee).append("-->").append(caller).append(": return\n");
                        break;
                }

                generateMethodCalls(diagramBuilder, methodCalls, callee);
            }
        }
    }

    private static String describeMethodCall(MethodCall call) {
        String methodName = call.methodSignature.substring(call.methodSignature.lastIndexOf('.') + 1);
        return methodName + "(" + inferParameterTypes(call) + ")";
    }

    private static String describeMethodReturn(MethodCall call) {
        return "return " + inferReturnType(call);
    }

    private static String inferParameterTypes(MethodCall call) {
        MethodDeclaration method = findMethodDeclaration(call.methodSignature);
        if (method != null) {
            return method.getParameters().stream()
                    .map(p -> p.getType().asString())
                    .collect(Collectors.joining(", "));
        }
        return "...";
    }

    private static String inferReturnType(MethodCall call) {
        MethodDeclaration method = findMethodDeclaration(call.methodSignature);
        if (method != null) {
            Type returnType = method.getType();
            if (returnType.isVoidType()) {
                return "void";
            }
            return returnType.asString();
        }
        return "Object";
    }

    private static MethodDeclaration findMethodDeclaration(String methodSignature) {
        for (Project project : projects.values()) {
            MethodDeclaration method = project.methods.get(methodSignature);
            if (method != null) {
                return method;
            }
        }
        return null;
    }

    private static String determineStereotype(String className) {
        if (className.endsWith("Controller")) return "Controller";
        if (className.endsWith("Service")) return "Service";
        if (className.endsWith("Repository")) return "Repository";
        if (className.endsWith("DTO")) return "DTO";
        return "Class";
    }

    private static class Project {
        String path;
        String name;
        Map<String, List<MethodCall>> methodCalls = new ConcurrentHashMap<>();
        Set<String> exposedAPIs = ConcurrentHashMap.newKeySet();
        Map<String, MethodDeclaration> methods = new ConcurrentHashMap<>();

        Project(String path, String name) {
            this.path = path;
            this.name = name;
        }

        void addMethod(String methodSignature, MethodDeclaration method) {
            methods.put(methodSignature, method);
        }

        void addMethodCall(String caller, MethodCall callee) {
            methodCalls.computeIfAbsent(caller, k -> new CopyOnWriteArrayList<>()).add(callee);
        }

        void addExposedAPI(String api) {
            exposedAPIs.add(api);
        }
    }

    public static class MethodCall {
        String methodSignature;
        String className;
        String methodBody;
        List<String> parameters;
        String returnType;

        public MethodCall(String methodSignature, String className, String methodBody, List<String> parameters, String returnType) {
            this.methodSignature = methodSignature;
            this.className = className;
            this.methodBody = methodBody;
            this.parameters = parameters;
            this.returnType = returnType;
        }
    }

    private static class MethodCallVisitor extends VoidVisitorAdapter<Void> {
        private Project project;
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
            String methodSignature = currentClass + "." + md.getSignature().asString();
            List<String> parameters = md.getParameters().stream()
                    .map(p -> p.getType().asString() + " " + p.getNameAsString())
                    .collect(Collectors.toList());
            String returnType = md.getType().asString();
            String methodBody = md.getBody().map(Object::toString).orElse("");

            if (isExposedAPI(md)) {
                project.addExposedAPI(methodSignature);
            }

            md.findAll(MethodCallExpr.class).forEach(mce -> {
                try {
                    ResolvedMethodDeclaration resolvedMethod = mce.resolve();
                    String calleeSignature = resolvedMethod.getQualifiedSignature();
                    String calleeClass = resolvedMethod.declaringType().getClassName();
                    MethodCall methodCall = new MethodCall(calleeSignature, calleeClass, methodBody, parameters, returnType);
                    project.addMethodCall(methodSignature, methodCall);
                } catch (Exception e) {
                    logger.warning("Failed to resolve method call: " + mce);
                }
            });
        }

        private boolean isExposedAPI(MethodDeclaration md) {
            return md.getAnnotationByName("GetMapping").isPresent() ||
                    md.getAnnotationByName("PostMapping").isPresent() ||
                    md.getAnnotationByName("PutMapping").isPresent() ||
                    md.getAnnotationByName("DeleteMapping").isPresent() ||
                    md.getAnnotationByName("RequestMapping").isPresent();
        }
    }
}