package com.example;

import com.github.javaparser.JavaParser;
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
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import org.springframework.beans.factory.annotation.Autowired;
import javax.inject.Inject;
import javax.annotation.Resource;


import javax.inject.Inject;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class SequenceDiagramGeneratorV3 {
    private static final Map<String, Project> projects = new ConcurrentHashMap<>();
    private static final Logger logger = Logger.getLogger(SequenceDiagramGeneratorV3.class.getName());
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

        addStandardLibrarySolver();
        addProjectSolvers();
        addExternalLibrarySolvers();

        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(typeSolver);
        parserConfiguration = new ParserConfiguration().setSymbolResolver(symbolSolver);
    }

    private static void addStandardLibrarySolver() {
        typeSolver.add(new ReflectionTypeSolver(false));  // For Java standard library types
    }

    private static void addProjectSolvers() {
        for (Project project : projects.values()) {
            typeSolver.add(new JavaParserTypeSolver(new File(project.path)));  // Project classes
        }
    }

    private static void addExternalLibrarySolvers() {
        // Add additional solvers as needed
        typeSolver.add(new ReflectionTypeSolver());
//        addExternalLibraryPath("path/to/your/external/libs");

        // Add JarTypeSolver for external libraries
//        addJarTypeSolver("path/to/external.jar");
    }

    private static void addExternalLibraryPath(String path) {
        typeSolver.add(new JavaParserTypeSolver(new File(path)));  // External library classes
    }

    private static void addJarTypeSolver(String jarPath) {
        try {
            typeSolver.add(new JarTypeSolver(Paths.get(jarPath)));  // External dependencies
        } catch (IOException e) {
            logger.severe("Failed to add external library: " + e.getMessage());
        }
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

            // Detect @Autowired or similar dependencies and map them to classes
            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cls -> {
                // Handle methods
                cls.getMethods().forEach(method -> {
                    String methodSignature = cls.getNameAsString() + "." + method.getSignature().asString();
                    project.addMethod(methodSignature, method);
                    if (isExposedAPI(method)) {
                        project.addExposedAPI(methodSignature);
                    }
                    processMethodCalls(project, cls.getNameAsString(), method);
                });

                cls.getFields().forEach(field -> {
                    if (field.isAnnotationPresent(Autowired.class) ||
                            field.isAnnotationPresent(Inject.class) ||
                            field.isAnnotationPresent(Resource.class)) {

                        String fieldType = field.getElementType().asString();
                        // Add the autowired field to the project
                        project.addAutowiredField(cls.getNameAsString(), field.getVariable(0).getNameAsString(), fieldType);
                    }
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
                // Caller signature (method within which this call is made)
                String callerSignature = className + "." + method.getSignature().asString();

                // Try to resolve the method being called
                ResolvedMethodDeclaration resolvedMethod = methodCall.resolve();

                // Check if the method being called belongs to an autowired field
                String calleeClassName = resolvedMethod.declaringType().getQualifiedName();
                if (project.isAutowiredField(className, methodCall.getNameAsString())) {
                    calleeClassName = project.getAutowiredFieldType(className, methodCall.getNameAsString());
                }

                // Fully qualified signature of the method being called
                String calleeSignature = resolvedMethod.getQualifiedSignature();

                // Collect parameter types of the callee method
                List<String> parameterTypes = new ArrayList<>();
                for (int i = 0; i < resolvedMethod.getNumberOfParams(); i++) {
                    parameterTypes.add(resolvedMethod.getParam(i).describeType());
                }

                // Return type of the callee method
                String returnType = resolvedMethod.getReturnType().describe();

                // Add the method call to the project, marking it as external if needed
                if (isExternalAPI(calleeClassName)) {
                    project.addMethodCall(callerSignature, new MethodCall(calleeSignature, "ExternalAPI", method.toString(),
                            parameterTypes, returnType));
                } else {
                    project.addMethodCall(callerSignature, new MethodCall(calleeSignature, calleeClassName, method.toString(),
                            parameterTypes, returnType));
                }
            } catch (UnsolvedSymbolException e) {
                // Handle unresolved symbols (e.g., missing imports, unknown types)
                logger.warning("Unsolved symbol in " + className + "." + method.getNameAsString() + ": " + methodCall);
                String callerSignature = className + "." + method.getSignature().asString();
                String calleeSignature = methodCall.getNameAsString() + "(...)";
                project.addMethodCall(callerSignature, new MethodCall(calleeSignature, "UnknownClass", method.toString(),
                        Collections.singletonList("Unknown"), "Unknown"));
            } catch (Exception e) {
                // General fallback for unexpected exceptions
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
        return className.contains("External") || className.contains("Client") || className.endsWith("ApiClient")
                || className.endsWith("ServiceClient") || apiEndpoints.containsKey(className);
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

    private static class Project {
        String path;
        String name;
        Map<String, List<MethodCall>> methodCalls = new ConcurrentHashMap<>();
        Set<String> exposedAPIs = ConcurrentHashMap.newKeySet();
        Map<String, MethodDeclaration> methods = new ConcurrentHashMap<>();// A map or list to store the autowired fields
        private Map<String, Map<String, String>> autowiredFields = new HashMap<>();


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

        // Check if a field is autowired in the given class
        public boolean isAutowiredField(String className, String fieldName) {
            // Retrieve the inner map associated with the className
            Map<String, String> fields = autowiredFields.get(className);
            return fields != null && fields.containsKey(fieldName);
        }

        // Retrieve the type of the autowired field
        public String getAutowiredFieldType(String className, String fieldName) {
            // Retrieve the inner map associated with the className
            Map<String, String> fields = autowiredFields.get(className);
            return fields != null ? fields.get(fieldName) : null;
        }

        // Add an autowired field to the map
        public void addAutowiredField(String className, String fieldName, String fieldType) {
            // Ensure that the outer map is a Map<String, Map<String, String>>
            autowiredFields.computeIfAbsent(className, k -> new HashMap<>())
                    .put(fieldName, fieldType);
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