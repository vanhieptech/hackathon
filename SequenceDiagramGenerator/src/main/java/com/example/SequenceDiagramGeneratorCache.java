package com.example;

import com.github.javaparser.ParseProblemException;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.sourceforge.plantuml.FileFormat;
import net.sourceforge.plantuml.FileFormatOption;
import net.sourceforge.plantuml.SourceStringReader;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import smile.classification.RandomForest;
import smile.data.DataFrame;
import smile.data.formula.Formula;
import smile.data.vector.IntVector;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SequenceDiagramGeneratorCache {
    private static final Map<String, Project> projects = new ConcurrentHashMap<>();
    private static final Map<String, Project> previousProjects = new ConcurrentHashMap<>();
    private static final Map<String, String> externalAPIs = new ConcurrentHashMap<>();
    private static int maxDepth = Integer.MAX_VALUE;
    private static Pattern includePattern = Pattern.compile(".*");
    private static Pattern excludePattern = Pattern.compile("^$");
    private static final int THREAD_POOL_SIZE = Runtime.getRuntime().availableProcessors();
    private static AtomicInteger processedFiles = new AtomicInteger(0);
    private static int totalFiles = 0;
    private static volatile boolean cancelled = false;
    private static final Logger logger = Logger.getLogger(SequenceDiagramGeneratorCache.class.getName());
    private static String outputFormat = "puml";
    private static boolean performDiff = false;
    private static final String CACHE_DIR = "analysis_cache";
    private static final Gson gson = new GsonBuilder().create();
    private static Map<String, Set<String>> dependencyGraph = new HashMap<>();
    private static RandomForest codeQualityModel;

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

        loadExternalAPIs("external_apis.txt");

        logger.info("Starting analysis of projects");
        long startTime = System.currentTimeMillis();

        for (String projectPath : projects.keySet()) {
            File projectDir = new File(projectPath);
            countFiles(projectDir);
            processDirectory(projectDir, projects.get(projectPath));
        }

        long endTime = System.currentTimeMillis();

        if (!cancelled) {
            logger.info("Analysis completed in " + (endTime - startTime) + " ms");
            System.out.println("\nAnalysis completed in " + (endTime - startTime) + " ms");
            mapInterProjectCalls();
            analyzeDependencies();
            generateMLInsights();
            generateSequenceDiagram();
            performDifferentialAnalysis();
            generateDependencyGraph();
            generateInteractiveDiagram();
        } else {
            logger.info("Analysis cancelled.");
            System.out.println("\nAnalysis cancelled.");
        }
    }

    private static void loadExternalAPIs(String filename) {
        try (Scanner scanner = new Scanner(new File(filename))) {
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine();
                String[] parts = line.split(",");
                if (parts.length == 2) {
                    externalAPIs.put(parts[0].trim(), parts[1].trim());
                }
            }
        } catch (FileNotFoundException e) {
            logger.warning("External APIs file not found: " + filename);
        }
    }

    private static void parseArguments(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--project=")) {
                String[] projectInfo = args[i].substring("--project=".length()).split(",");
                if (projectInfo.length == 2) {
                    projects.put(projectInfo[0], new Project(projectInfo[0], projectInfo[1]));
                }
            } else if (args[i].startsWith("--previous-project=")) {
                String[] projectInfo = args[i].substring("--previous-project=".length()).split(",");
                if (projectInfo.length == 2) {
                    previousProjects.put(projectInfo[0], new Project(projectInfo[0], projectInfo[1]));
                    performDiff = true;
                }
            } else if (args[i].startsWith("--max-depth=")) {
                maxDepth = Integer.parseInt(args[i].substring("--max-depth=".length()));
            } else if (args[i].startsWith("--include=")) {
                includePattern = Pattern.compile(args[i].substring("--include=".length()));
            } else if (args[i].startsWith("--exclude=")) {
                excludePattern = Pattern.compile(args[i].substring("--exclude=".length()));
            } else if (args[i].startsWith("--output-format=")) {
                outputFormat = args[i].substring("--output-format=".length()).toLowerCase();
            }
        }
    }

    private static void countFiles(File directory) {
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    countFiles(file);
                } else if (file.getName().endsWith(".java")) {
                    totalFiles++;
                }
            }
        }
    }

    private static void processDirectory(File directory, Project project) {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        List<Future<?>> futures = new ArrayList<>();

        try {
            processDirectoryRecursive(directory, executor, futures, project);

            ScheduledExecutorService progressReporter = Executors.newSingleThreadScheduledExecutor();
            progressReporter.scheduleAtFixedRate(() -> reportProgress(), 0, 1, TimeUnit.SECONDS);

            for (Future<?> future : futures) {
                if (cancelled) {
                    future.cancel(true);
                } else {
                    future.get(); // Wait for all tasks to complete
                }
            }

            progressReporter.shutdown();
        } catch (InterruptedException | ExecutionException e) {
            e.printStackTrace();
        } finally {
            executor.shutdown();
        }
    }

    private static void processDirectoryRecursive(File directory, ExecutorService executor, List<Future<?>> futures, Project project) {
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (cancelled) return;
                if (file.isDirectory()) {
                    processDirectoryRecursive(file, executor, futures, project);
                } else if (file.getName().endsWith(".java")) {
                    futures.add(executor.submit(() -> processJavaFile(file, project)));
                }
            }
        }
    }

    private static void processJavaFile(File file, Project project) {
        if (cancelled) return;

        try {
            String cacheKey = getCacheKey(file);
            Optional<ProjectCache> cachedResult = loadFromCache(cacheKey);

            if (cachedResult.isPresent() && !hasFileChanged(file, cachedResult.get().lastModified)) {
                restoreFromCache(project, cachedResult.get());
                logger.fine("Loaded cached data for file: " + file.getPath());
            } else {
                CompilationUnit cu = parseJavaFile(file);
                if (cu != null) {
                    MethodCallVisitor visitor = new MethodCallVisitor(project);
                    cu.accept(visitor, null);

                    ProjectCache newCache = new ProjectCache(
                            file.lastModified(),
                            visitor.getMethodCalls(),
                            visitor.getExposedAPIs(),
                            visitor.getMethodBodies()
                    );
                    saveToCache(cacheKey, newCache);
                    logger.fine("Processed and cached file: " + file.getPath());
                }
            }

            processedFiles.incrementAndGet();
        } catch (IOException e) {
            logger.warning("IO error processing file " + file.getPath() + ": " + e.getMessage());
        } catch (Exception e) {
            logger.severe("Unexpected error processing file " + file.getPath() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static CompilationUnit parseJavaFile(File file) {
        try {
            return StaticJavaParser.parse(file);
        } catch (ParseProblemException e) {
            logger.warning("Parse error in file " + file.getPath() + ": " + e.getMessage());
        } catch (IOException e) {
            logger.warning("IO error parsing file " + file.getPath() + ": " + e.getMessage());
        }
        return null;
    }

    private static void reportProgress() {
        int processed = processedFiles.get();
        double progress = (double) processed / totalFiles * 100;
        System.out.printf("\rProgress: %.2f%% (%d/%d files processed)", progress, processed, totalFiles);
        System.out.flush();
    }

    private static String getCacheKey(File file) throws IOException {
        String relativePath = file.getCanonicalPath().replace(File.separator, "_");
        return relativePath + "_" + file.lastModified();
    }

    private static Optional<ProjectCache> loadFromCache(String cacheKey) {
        Path cachePath = Paths.get(CACHE_DIR, cacheKey + ".json");
        if (Files.exists(cachePath)) {
            try {
                String cacheContent = new String(Files.readAllBytes(cachePath));
                return Optional.of(gson.fromJson(cacheContent, ProjectCache.class));
            } catch (IOException e) {
                logger.warning("Error reading cache: " + e.getMessage());
            }
        }
        return Optional.empty();
    }

    private static void saveToCache(String cacheKey, ProjectCache cache) {
        try {
            Files.createDirectories(Paths.get(CACHE_DIR));
            Path cachePath = Paths.get(CACHE_DIR, cacheKey + ".json");
            String cacheContent = gson.toJson(cache);
            Files.write(cachePath, cacheContent.getBytes());
        } catch (IOException e) {
            logger.warning("Error writing to cache: " + e.getMessage());
        }
    }

    private static boolean hasFileChanged(File file, long cachedLastModified) {
        return file.lastModified() > cachedLastModified;
    }


    private static void restoreFromCache(Project project, ProjectCache cache) {
        if (cache.methodCalls != null) {
            project.methodCalls.putAll(cache.methodCalls);
        } else {
            logger.warning("Cached methodCalls is null");
        }

        if (cache.exposedAPIs != null) {
            project.exposedAPIs.addAll(cache.exposedAPIs);
        } else {
            logger.warning("Cached exposedAPIs is null");
        }

        if (cache.methodBodies != null) {
            project.methodBodies.putAll(cache.methodBodies);
        } else {
            logger.warning("Cached methodBodies is null");
        }
    }

    private static class ProjectCache {
        long lastModified;
        Map<String, List<MethodCall>> methodCalls;
        Set<String> exposedAPIs;
        Map<String, String> methodBodies;

        ProjectCache(long lastModified, Map<String, List<MethodCall>> methodCalls,
                     Set<String> exposedAPIs, Map<String, String> methodBodies) {
            this.lastModified = lastModified;
            this.methodCalls = methodCalls != null ? new HashMap<>(methodCalls) : new HashMap<>();
            this.exposedAPIs = exposedAPIs != null ? new HashSet<>(exposedAPIs) : new HashSet<>();
            this.methodBodies = methodBodies != null ? new HashMap<>(methodBodies) : new HashMap<>();
        }
    }

    private static class MethodCallVisitor extends VoidVisitorAdapter<Void> {
        private String currentClass;
        private Project project;
        private Map<String, List<MethodCall>> methodCalls = new HashMap<>();
        private Set<String> exposedAPIs = new HashSet<>();
        private Map<String, String> methodBodies = new HashMap<>();

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
            MethodCall methodCall = new MethodCall(methodSignature, currentClass);

            // Capture method body
            md.getBody().ifPresent(body -> {
                methodCall.methodBody = body.toString();

                // Parse method body for specific operations
                body.walk(Node.class, node -> {
                    if (node instanceof MethodCallExpr) {
                        MethodCallExpr call = (MethodCallExpr) node;
                        String callName = call.getNameAsString();

                        if (isDatabaseOperation(call)) {
                            methodCall.addDatabaseOperation(call.toString());
                        } else if (isApiCall(call)) {
                            methodCall.addApiCall(call.toString());
                        } else if (isServiceCreation(call)) {
                            methodCall.addServiceCreation(call.toString());
                        }
                    }
                });
            });

            project.addMethodCall(methodSignature, methodCall);
            super.visit(md, arg);
        }

        private boolean isDatabaseOperation(MethodCallExpr call) {
            return call.getScope().map(scope ->
                    scope.toString().contains("Repository") ||
                            scope.toString().contains("repository")
            ).orElse(false);
        }

        private boolean isApiCall(MethodCallExpr call) {
            return call.getScope().map(scope ->
                    scope.toString().contains("restTemplate") ||
                            scope.toString().contains("webClient")
            ).orElse(false);
        }

        private boolean isServiceCreation(MethodCallExpr call) {
            return call.toString().contains("new") && call.toString().contains("Service");
        }

        public Map<String, List<MethodCall>> getMethodCalls() {
            return methodCalls;
        }

        public Set<String> getExposedAPIs() {
            return exposedAPIs;
        }

        public Map<String, String> getMethodBodies() {
            return methodBodies;
        }
    }


    public static class MethodCall {
        String methodSignature;
        String className;
        String methodBody;
        List<String> databaseOperations;
        List<String> apiCalls;
        List<String> serviceCreations;

        public MethodCall(String methodSignature, String className) {
            this.methodSignature = methodSignature;
            this.className = className;
            this.methodBody = "";
            this.databaseOperations = new ArrayList<>();
            this.apiCalls = new ArrayList<>();
            this.serviceCreations = new ArrayList<>();
        }

        public void addDatabaseOperation(String operation) {
            this.databaseOperations.add(operation);
        }

        public void addApiCall(String apiCall) {
            this.apiCalls.add(apiCall);
        }

        public void addServiceCreation(String serviceCreation) {
            this.serviceCreations.add(serviceCreation);
        }
    }

    private static class Project {
        String path;
        String name;
        Map<String, List<MethodCall>> methodCalls;
        Set<String> exposedAPIs;
        Map<String, String> methodBodies;

        Project(String path, String name) {
            this.path = path;
            this.name = name;
            this.methodCalls = new ConcurrentHashMap<>();
            this.exposedAPIs = ConcurrentHashMap.newKeySet();
            this.methodBodies = new ConcurrentHashMap<>();
        }

        void addMethodCall(String caller, MethodCall callee) {
            methodCalls.computeIfAbsent(caller, k -> new CopyOnWriteArrayList<>()).add(callee);
        }

        void addExposedAPI(String api) {
            exposedAPIs.add(api);
        }
    }

    private static void generateInteractiveDiagram() {
        StringBuilder htmlBuilder = new StringBuilder();
        htmlBuilder.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n");
        htmlBuilder.append("<meta charset=\"UTF-8\">\n");
        htmlBuilder.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        htmlBuilder.append("<title>Interactive Sequence Diagram</title>\n");
        htmlBuilder.append("<script src=\"https://unpkg.com/vis-network/standalone/umd/vis-network.min.js\"></script>\n");
        htmlBuilder.append("<link rel=\"stylesheet\" href=\"https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.5.1/styles/default.min.css\">\n");
        htmlBuilder.append("<script src=\"https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.5.1/highlight.min.js\"></script>\n");
        htmlBuilder.append("<script src=\"https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.5.1/languages/java.min.js\"></script>\n");
        htmlBuilder.append("<style>\n");
        htmlBuilder.append("  body { display: flex; height: 100vh; margin: 0; }\n");
        htmlBuilder.append("  #controls { padding: 10px; }\n");
        htmlBuilder.append("  #controls input, #controls select, #controls button { margin-bottom: 5px; }\n");
        htmlBuilder.append("  #diagram-container { display: flex; flex-grow: 1; }\n");
        htmlBuilder.append("  #mynetwork { flex-grow: 1; border: 1px solid lightgray; }\n");
        htmlBuilder.append("  #details-panel { width: 400px; padding: 10px; border-left: 1px solid #ccc; overflow-y: auto; }\n");
        htmlBuilder.append("  #details-panel h3 { margin-top: 0; }\n");
        htmlBuilder.append("  pre { margin: 0; white-space: pre-wrap; word-wrap: break-word; }\n");
        htmlBuilder.append("  code { font-size: 14px; }\n");
        htmlBuilder.append("</style>\n");
        htmlBuilder.append("</head>\n<body>\n");
        htmlBuilder.append("<div id=\"controls\">\n");
        htmlBuilder.append("  <input type=\"text\" id=\"searchInput\" placeholder=\"Search...\">\n");
        htmlBuilder.append("  <select id=\"projectFilter\"><option value=\"\">All Projects</option></select>\n");
        htmlBuilder.append("  <button onclick=\"applyFilters()\">Apply Filters</button>\n");
        htmlBuilder.append("  <button onclick=\"resetFilters()\">Reset Filters</button>\n");
        htmlBuilder.append("  <button onclick=\"expandAll()\">Expand All</button>\n");
        htmlBuilder.append("  <button onclick=\"collapseAll()\">Collapse All</button>\n");
        htmlBuilder.append("</div>\n");
        htmlBuilder.append("<div id=\"diagram-container\">\n");
        htmlBuilder.append("  <div id=\"mynetwork\"></div>\n");
        htmlBuilder.append("  <div id=\"details-panel\">\n");
        htmlBuilder.append("    <h3>Node Details</h3>\n");
        htmlBuilder.append("    <div id=\"node-details\">Select a node to view details</div>\n");
        htmlBuilder.append("  </div>\n");
        htmlBuilder.append("</div>\n");
        htmlBuilder.append("<script>\n");

        // Generate nodes and edges for the diagram
        List<String> nodes = new ArrayList<>();
        List<String> edges = new ArrayList<>();
        Set<String> projectNames = new HashSet<>();
        int nodeId = 0;
        Map<String, Integer> nodeMap = new HashMap<>();

        StringBuilder nodeDetailsBuilder = new StringBuilder();
        nodeDetailsBuilder.append("var nodeDetails = {\n");

        for (Project project : projects.values()) {
            projectNames.add(project.name);
            int projectNodeId = nodeId++;
            nodes.add(String.format("{ id: %d, label: '%s', level: 1, project: '%s' }", projectNodeId, project.name, project.name));
            nodeDetailsBuilder.append(String.format("  %d: { type: 'Project', name: '%s', classes: %d },\n",
                    projectNodeId, project.name, project.methodCalls.size()));

            Map<String, Integer> classNodes = new HashMap<>();

            for (Map.Entry<String, List<MethodCall>> entry : project.methodCalls.entrySet()) {
                String caller = entry.getKey();
                String className = caller.substring(0, caller.lastIndexOf('.'));
                String methodName = caller.substring(caller.lastIndexOf('.') + 1);

                if (!classNodes.containsKey(className)) {
                    int classNodeId = nodeId++;
                    classNodes.put(className, classNodeId);
                    nodes.add(String.format("{ id: %d, label: '%s', level: 2, project: '%s' }", classNodeId, className, project.name));
                    edges.add(String.format("{ from: %d, to: %d }", projectNodeId, classNodeId));
                    nodeDetailsBuilder.append(String.format("  %d: { type: 'Class', name: '%s', project: '%s', methods: %d },\n",
                            classNodeId, className, project.name, entry.getValue().size()));
                }

                int methodNodeId = nodeId++;
                nodeMap.put(caller, methodNodeId);
                nodes.add(String.format("{ id: %d, label: '%s', level: 3, project: '%s' }", methodNodeId, methodName, project.name));
                edges.add(String.format("{ from: %d, to: %d }", classNodes.get(className), methodNodeId));

                String escapedMethodBody = StringEscapeUtils.escapeEcmaScript(project.methodBodies.getOrDefault(caller, ""));
                nodeDetailsBuilder.append(String.format("  %d: { type: 'Method', name: '%s', class: '%s', project: '%s', calls: %d, body: '%s' },\n",
                        methodNodeId, methodName, className, project.name, entry.getValue().size(), escapedMethodBody));

                for (MethodCall callee : entry.getValue()) {
                    if (!nodeMap.containsKey(callee.methodSignature)) {
                        String calleeClassName = callee.methodSignature.substring(0, callee.methodSignature.lastIndexOf('.'));
                        String calleeMethodName = callee.methodSignature.substring(callee.methodSignature.lastIndexOf('.') + 1);

                        if (!classNodes.containsKey(calleeClassName)) {
                            int calleeClassNodeId = nodeId++;
                            classNodes.put(calleeClassName, calleeClassNodeId);
                            nodes.add(String.format("{ id: %d, label: '%s', level: 2, project: '%s' }", calleeClassNodeId, calleeClassName, project.name));
                            edges.add(String.format("{ from: %d, to: %d }", projectNodeId, calleeClassNodeId));
                        }

                        int calleeMethodNodeId = nodeId++;
                        nodeMap.put(callee.methodSignature, calleeMethodNodeId);
                        nodes.add(String.format("{ id: %d, label: '%s', level: 3, project: '%s' }", calleeMethodNodeId, calleeMethodName, project.name));
                        edges.add(String.format("{ from: %d, to: %d }", classNodes.get(calleeClassName), calleeMethodNodeId));
                    }
                    edges.add(String.format("{ from: %d, to: %d }", nodeMap.get(caller), nodeMap.get(callee.methodSignature)));
                }
            }
        }

        nodeDetailsBuilder.append("};\n");

        htmlBuilder.append("  var nodes = new vis.DataSet([\n");
        htmlBuilder.append(String.join(",\n", nodes));
        htmlBuilder.append("\n  ]);\n");

        htmlBuilder.append("  var edges = new vis.DataSet([\n");
        htmlBuilder.append(String.join(",\n", edges));
        htmlBuilder.append("\n  ]);\n");

        htmlBuilder.append(nodeDetailsBuilder.toString());

        htmlBuilder.append("  var container = document.getElementById('mynetwork');\n");
        htmlBuilder.append("  var data = { nodes: nodes, edges: edges };\n");
        htmlBuilder.append("  var options = { \n");
        htmlBuilder.append("    physics: { enabled: false },\n");
        htmlBuilder.append("    layout: { hierarchical: { direction: 'UD', sortMethod: 'directed' } },\n");
        htmlBuilder.append("    interaction: { navigationButtons: true, keyboard: true }\n");
        htmlBuilder.append("  };\n");
        htmlBuilder.append("  var network = new vis.Network(container, data, options);\n");

        // Add node selection handler
        htmlBuilder.append("  network.on('select', function(params) {\n");
        htmlBuilder.append("    if (params.nodes.length === 1) {\n");
        htmlBuilder.append("      var nodeId = params.nodes[0];\n");
        htmlBuilder.append("      var details = nodeDetails[nodeId];\n");
        htmlBuilder.append("      var detailsHtml = '';\n");
        htmlBuilder.append("      if (details) {\n");
        htmlBuilder.append("        detailsHtml += '<p><strong>Type:</strong> ' + details.type + '</p>';\n");
        htmlBuilder.append("        detailsHtml += '<p><strong>Name:</strong> ' + details.name + '</p>';\n");
        htmlBuilder.append("        if (details.type === 'Project') {\n");
        htmlBuilder.append("          detailsHtml += '<p><strong>Classes:</strong> ' + details.classes + '</p>';\n");
        htmlBuilder.append("        } else if (details.type === 'Class') {\n");
        htmlBuilder.append("          detailsHtml += '<p><strong>Project:</strong> ' + details.project + '</p>';\n");
        htmlBuilder.append("          detailsHtml += '<p><strong>Methods:</strong> ' + details.methods + '</p>';\n");
        htmlBuilder.append("        } else if (details.type === 'Method') {\n");
        htmlBuilder.append("          detailsHtml += '<p><strong>Class:</strong> ' + details.class + '</p>';\n");
        htmlBuilder.append("          detailsHtml += '<p><strong>Project:</strong> ' + details.project + '</p>';\n");
        htmlBuilder.append("          detailsHtml += '<p><strong>Calls:</strong> ' + details.calls + '</p>';\n");
        htmlBuilder.append("        }\n");
        htmlBuilder.append("      } else {\n");
        htmlBuilder.append("        detailsHtml = 'No details available for this node.';\n");
        htmlBuilder.append("      }\n");
        htmlBuilder.append("      document.getElementById('node-details').innerHTML = detailsHtml;\n");
        htmlBuilder.append("    } else {\n");
        htmlBuilder.append("      document.getElementById('node-details').innerHTML = 'Select a node to view details';\n");
        htmlBuilder.append("    }\n");
        htmlBuilder.append("  });\n");

        // Add filtering and hierarchical view functionality
        htmlBuilder.append("  function applyFilters() {\n");
        htmlBuilder.append("    var searchValue = document.getElementById('searchInput').value.toLowerCase();\n");
        htmlBuilder.append("    var projectValue = document.getElementById('projectFilter').value;\n");
        htmlBuilder.append("    nodes.forEach(function(node) {\n");
        htmlBuilder.append("      var match = (node.label.toLowerCase().indexOf(searchValue) !== -1) &&\n");
        htmlBuilder.append("                  (projectValue === '' || node.project === projectValue);\n");
        htmlBuilder.append("      nodes.update({id: node.id, hidden: !match});\n");
        htmlBuilder.append("    });\n");
        htmlBuilder.append("  }\n");

        htmlBuilder.append("  function resetFilters() {\n");
        htmlBuilder.append("    document.getElementById('searchInput').value = '';\n");
        htmlBuilder.append("    document.getElementById('projectFilter').value = '';\n");
        htmlBuilder.append("    nodes.forEach(function(node) {\n");
        htmlBuilder.append("      nodes.update({id: node.id, hidden: false});\n");
        htmlBuilder.append("    });\n");
        htmlBuilder.append("  }\n");

        htmlBuilder.append("  function expandAll() {\n");
        htmlBuilder.append("    nodes.forEach(function(node) {\n");
        htmlBuilder.append("      nodes.update({id: node.id, hidden: false});\n");
        htmlBuilder.append("    });\n");
        htmlBuilder.append("  }\n");

        htmlBuilder.append("  function collapseAll() {\n");
        htmlBuilder.append("    nodes.forEach(function(node) {\n");
        htmlBuilder.append("      nodes.update({id: node.id, hidden: node.level > 1});\n");
        htmlBuilder.append("    });\n");
        htmlBuilder.append("  }\n");

        htmlBuilder.append("  network.on('doubleClick', function(params) {\n");
        htmlBuilder.append("    if (params.nodes.length === 1) {\n");
        htmlBuilder.append("      var nodeId = params.nodes[0];\n");
        htmlBuilder.append("      var clickedNode = nodes.get(nodeId);\n");
        htmlBuilder.append("      var connectedNodes = network.getConnectedNodes(nodeId);\n");
        htmlBuilder.append("      var updateArray = [];\n");
        htmlBuilder.append("      connectedNodes.forEach(function(connectedNodeId) {\n");
        htmlBuilder.append("        var connectedNode = nodes.get(connectedNodeId);\n");
        htmlBuilder.append("        if (connectedNode.level > clickedNode.level) {\n");
        htmlBuilder.append("          updateArray.push({id: connectedNodeId, hidden: !connectedNode.hidden});\n");
        htmlBuilder.append("        }\n");
        htmlBuilder.append("      });\n");
        htmlBuilder.append("      nodes.update(updateArray);\n");
        htmlBuilder.append("    }\n");
        htmlBuilder.append("  });\n");

        // Update node selection handler to include syntax highlighted code snippet
        htmlBuilder.append("  network.on('select', function(params) {\n");
        htmlBuilder.append("    if (params.nodes.length === 1) {\n");
        htmlBuilder.append("      var nodeId = params.nodes[0];\n");
        htmlBuilder.append("      var details = nodeDetails[nodeId];\n");
        htmlBuilder.append("      var detailsHtml = '';\n");
        htmlBuilder.append("      if (details) {\n");
        htmlBuilder.append("        detailsHtml += '<p><strong>Type:</strong> ' + details.type + '</p>';\n");
        htmlBuilder.append("        detailsHtml += '<p><strong>Name:</strong> ' + details.name + '</p>';\n");
        htmlBuilder.append("        if (details.type === 'Project') {\n");
        htmlBuilder.append("          detailsHtml += '<p><strong>Classes:</strong> ' + details.classes + '</p>';\n");
        htmlBuilder.append("        } else if (details.type === 'Class') {\n");
        htmlBuilder.append("          detailsHtml += '<p><strong>Project:</strong> ' + details.project + '</p>';\n");
        htmlBuilder.append("          detailsHtml += '<p><strong>Methods:</strong> ' + details.methods + '</p>';\n");
        htmlBuilder.append("        } else if (details.type === 'Method') {\n");
        htmlBuilder.append("          detailsHtml += '<p><strong>Class:</strong> ' + details.class + '</p>';\n");
        htmlBuilder.append("          detailsHtml += '<p><strong>Project:</strong> ' + details.project + '</p>';\n");
        htmlBuilder.append("          detailsHtml += '<p><strong>Calls:</strong> ' + details.calls + '</p>';\n");
        htmlBuilder.append("          detailsHtml += '<p><strong>Code:</strong></p>';\n");
        htmlBuilder.append("          detailsHtml += '<pre><code class=\"java\">' + details.body + '</code></pre>';\n");
        htmlBuilder.append("        }\n");
        htmlBuilder.append("      } else {\n");
        htmlBuilder.append("        detailsHtml = 'No details available for this node.';\n");
        htmlBuilder.append("      }\n");
        htmlBuilder.append("      document.getElementById('node-details').innerHTML = detailsHtml;\n");
        htmlBuilder.append("      if (details && details.type === 'Method') {\n");
        htmlBuilder.append("        hljs.highlightAll();\n");
        htmlBuilder.append("      }\n");
        htmlBuilder.append("    } else {\n");
        htmlBuilder.append("      document.getElementById('node-details').innerHTML = 'Select a node to view details';\n");
        htmlBuilder.append("    }\n");
        htmlBuilder.append("  });\n");

        // Populate project filter dropdown
        htmlBuilder.append("  var projectFilter = document.getElementById('projectFilter');\n");
        for (String projectName : projectNames) {
            htmlBuilder.append("  projectFilter.innerHTML += '<option value=\"").append(projectName).append("\">").append(projectName).append("</option>';\n");
        }

        htmlBuilder.append("</script>\n");
        htmlBuilder.append("</body>\n</html>");

        try (FileWriter writer = new FileWriter("interactive_diagram.html")) {
            writer.write(htmlBuilder.toString());
            logger.info("Interactive diagram generated: interactive_diagram.html");
        } catch (IOException e) {
            logger.severe("Error generating interactive diagram: " + e.getMessage());
        }
    }

    private static void performDifferentialAnalysis() {
        if (!performDiff) return;

        StringBuilder diffBuilder = new StringBuilder();
        diffBuilder.append("Differential Analysis Report:\n\n");

        for (String projectPath : projects.keySet()) {
            Project currentProject = projects.get(projectPath);
            Project previousProject = previousProjects.get(projectPath);

            if (previousProject == null) {
                diffBuilder.append("No previous version found for project: ").append(currentProject.name).append("\n");
                continue;
            }

            diffBuilder.append("Changes in project: ").append(currentProject.name).append("\n");

            // Compare method calls
            Set<String> currentMethods = new HashSet<>(currentProject.methodCalls.keySet());
            Set<String> previousMethods = new HashSet<>(previousProject.methodCalls.keySet());

            Set<String> newMethods = new HashSet<>(currentMethods);
            newMethods.removeAll(previousMethods);

            Set<String> removedMethods = new HashSet<>(previousMethods);
            removedMethods.removeAll(currentMethods);

            diffBuilder.append("  New methods: ").append(newMethods).append("\n");
            diffBuilder.append("  Removed methods: ").append(removedMethods).append("\n");

            // Compare exposed APIs
            Set<String> newAPIs = new HashSet<>(currentProject.exposedAPIs);
            newAPIs.removeAll(previousProject.exposedAPIs);

            Set<String> removedAPIs = new HashSet<>(previousProject.exposedAPIs);
            removedAPIs.removeAll(currentProject.exposedAPIs);

            diffBuilder.append("  New exposed APIs: ").append(newAPIs).append("\n");
            diffBuilder.append("  Removed exposed APIs: ").append(removedAPIs).append("\n\n");
        }

        try (FileWriter writer = new FileWriter("differential_analysis.txt")) {
            writer.write(diffBuilder.toString());
            logger.info("Differential analysis report generated: differential_analysis.txt");
        } catch (IOException e) {
            logger.severe("Error writing differential analysis report: " + e.getMessage());
        }
    }

    private static void mapInterProjectCalls() {
        for (Project project : projects.values()) {
            for (Map.Entry<String, List<MethodCall>> entry : project.methodCalls.entrySet()) {
                for (MethodCall call : entry.getValue()) {
                    for (Project otherProject : projects.values()) {
                        if (otherProject != project && otherProject.exposedAPIs.contains(call.methodSignature)) {
                            logger.info("Inter-project call detected: " + project.name + " -> " + otherProject.name + " : " + call.methodSignature);
                        }
                    }
                }
            }
        }
    }

    private static void analyzeDependencies() {
        for (Project project : projects.values()) {
            analyzeMavenDependencies(project);
            analyzeCodeDependencies(project);
        }
        visualizeDependencyGraph();
    }

    private static void analyzeMavenDependencies(Project project) {
        File pomFile = new File(project.path, "pom.xml");
        if (pomFile.exists()) {
            try {
                MavenXpp3Reader reader = new MavenXpp3Reader();
                Model model = reader.read(new FileReader(pomFile));
                for (org.apache.maven.model.Dependency dependency : model.getDependencies()) {
                    String key = project.name + ":" + dependency.getGroupId() + ":" + dependency.getArtifactId();
                    dependencyGraph.computeIfAbsent(project.name, k -> new HashSet<>()).add(key);
                }
            } catch (Exception e) {
                logger.warning("Error parsing pom.xml for project " + project.name + ": " + e.getMessage());
            }
        }
    }

    private static void analyzeCodeDependencies(Project project) {
        for (Map.Entry<String, List<MethodCall>> entry : project.methodCalls.entrySet()) {
            String caller = entry.getKey();
            for (MethodCall callee : entry.getValue()) {
                if (!callee.className.startsWith(project.name)) {
                    dependencyGraph.computeIfAbsent(project.name, k -> new HashSet<>()).add(callee.className);
                }
            }
        }
    }

    private static void visualizeDependencyGraph() {
        StringBuilder graphBuilder = new StringBuilder();
        graphBuilder.append("digraph DependencyGraph {\n");
        for (Map.Entry<String, Set<String>> entry : dependencyGraph.entrySet()) {
            String project = entry.getKey();
            for (String dependency : entry.getValue()) {
                graphBuilder.append("  \"").append(project).append("\" -> \"").append(dependency).append("\";\n");
            }
        }
        graphBuilder.append("}\n");

        try (FileWriter writer = new FileWriter("dependency_graph.dot")) {
            writer.write(graphBuilder.toString());
            logger.info("Dependency graph generated: dependency_graph.dot");
        } catch (IOException e) {
            logger.severe("Error generating dependency graph: " + e.getMessage());
        }
    }

    private static class CodeMetrics {
        int cyclomaticComplexity;
        int linesOfCode;
        int numberOfParameters;
        int depthOfInheritance;
        int numberOfMethodCalls;
        double commentRatio;
        int numberOfLoops;
        int numberOfBranches;

        public double[] toArray() {
            return new double[]{
                    cyclomaticComplexity, linesOfCode, numberOfParameters, depthOfInheritance,
                    numberOfMethodCalls, commentRatio, numberOfLoops, numberOfBranches
            };
        }
    }

    private static class MetricsVisitor extends VoidVisitorAdapter<CodeMetrics> {
        @Override
        public void visit(MethodDeclaration md, CodeMetrics metrics) {
            super.visit(md, metrics);

            metrics.linesOfCode = md.getEnd().get().line - md.getBegin().get().line + 1;
            metrics.numberOfParameters = md.getParameters().size();
            metrics.numberOfMethodCalls = md.findAll(MethodCallExpr.class).size();

            // Calculate cyclomatic complexity
            metrics.cyclomaticComplexity = 1 + md.findAll(MethodCallExpr.class, m -> m.getNameAsString().equals("if") ||
                    m.getNameAsString().equals("while") ||
                    m.getNameAsString().equals("for") ||
                    m.getNameAsString().equals("case")).size();

            // Calculate comment ratio
            int commentLines = md.getAllContainedComments().stream()
                    .mapToInt(c -> c.getEnd().get().line - c.getBegin().get().line + 1)
                    .sum();
            metrics.commentRatio = (double) commentLines / metrics.linesOfCode;

            // Count loops and branches
            metrics.numberOfLoops = md.findAll(MethodCallExpr.class, m -> m.getNameAsString().equals("while") ||
                    m.getNameAsString().equals("for")).size();
            metrics.numberOfBranches = md.findAll(MethodCallExpr.class, m -> m.getNameAsString().equals("if") ||
                    m.getNameAsString().equals("switch")).size();
        }
    }

    private static void generateMLInsights() {
        Map<String, CodeMetrics> allMetrics = collectAllCodeMetrics();
        if (allMetrics.isEmpty()) {
            logger.warning("No metrics collected. Skipping ML insights generation.");
            return;
        }

        trainCodeQualityModel(allMetrics);

        if (codeQualityModel == null) {
            logger.warning("Code quality model could not be trained. Skipping quality analysis.");
            return;
        }

        for (Map.Entry<String, CodeMetrics> entry : allMetrics.entrySet()) {
            analyzeMethodQuality(entry.getKey(), entry.getValue());
        }
    }

    private static Map<String, CodeMetrics> collectAllCodeMetrics() {
        Map<String, CodeMetrics> allMetrics = new HashMap<>();
        for (Project project : projects.values()) {
            for (Map.Entry<String, List<MethodCall>> entry : project.methodCalls.entrySet()) {
                String methodSignature = entry.getKey();
                CodeMetrics metrics = collectMethodMetrics(project, methodSignature);
                allMetrics.put(methodSignature, metrics);
            }
        }
        return allMetrics;
    }

    private static CodeMetrics collectMethodMetrics(Project project, String methodSignature) {
        CodeMetrics metrics = new CodeMetrics();
        MetricsVisitor visitor = new MetricsVisitor();

        // Assuming we have a way to get the MethodDeclaration from the methodSignature
        MethodDeclaration md = getMethodDeclaration(project, methodSignature);
        if (md != null) {
            visitor.visit(md, metrics);
        }

        // Set depth of inheritance (this would require additional analysis of the class hierarchy)
        metrics.depthOfInheritance = calculateDepthOfInheritance(project, methodSignature);

        return metrics;
    }

    private static void trainCodeQualityModel(Map<String, CodeMetrics> allMetrics) {
        List<double[]> features = new ArrayList<>();
        List<Integer> labels = new ArrayList<>();

        for (CodeMetrics metrics : allMetrics.values()) {
            features.add(metrics.toArray());
            labels.add((metrics.cyclomaticComplexity < 10 && metrics.linesOfCode < 100) ? 1 : 0);
        }

        if (features.isEmpty()) {
            logger.warning("No metrics collected. Unable to train the code quality model.");
            return;
        }

        double[][] featureArray = features.toArray(new double[0][]);
        int[] labelArray = labels.stream().mapToInt(Integer::intValue).toArray();

        String[] columnNames = {
                "cyclomaticComplexity", "linesOfCode", "numberOfParameters", "depthOfInheritance",
                "numberOfMethodCalls", "commentRatio", "numberOfLoops", "numberOfBranches"
        };

        DataFrame df = DataFrame.of(featureArray, columnNames);
        df = df.merge(IntVector.of("quality", labelArray));

        codeQualityModel = RandomForest.fit(Formula.lhs("quality"), df);
    }

    private static void suggestImprovements(String methodSignature, CodeMetrics metrics) {
        if (metrics.cyclomaticComplexity > 10) {
            logger.info("Suggestion for " + methodSignature + ": Consider breaking down the method into smaller, more focused methods to reduce complexity.");
        }
        if (metrics.linesOfCode > 100) {
            logger.info("Suggestion for " + methodSignature + ": The method is quite long. Consider refactoring it into smaller methods.");
        }
        if (metrics.numberOfParameters > 5) {
            logger.info("Suggestion for " + methodSignature + ": The method has many parameters. Consider using parameter objects or builder pattern.");
        }
        if (metrics.commentRatio < 0.1) {
            logger.info("Suggestion for " + methodSignature + ": The method could benefit from more comments to improve readability.");
        }
        if (metrics.numberOfLoops + metrics.numberOfBranches > 5) {
            logger.info("Suggestion for " + methodSignature + ": The method has many loops and branches. Consider simplifying the logic or breaking it down.");
        }
    }

    private static void analyzeMethodQuality(String methodSignature, CodeMetrics metrics) {
        if (codeQualityModel == null) {
            logger.warning("Code quality model is not trained. Cannot analyze method quality.");
            return;
        }

        double[] features = metrics.toArray();
        String[] columnNames = {
                "cyclomaticComplexity", "linesOfCode", "numberOfParameters", "depthOfInheritance",
                "numberOfMethodCalls", "commentRatio", "numberOfLoops", "numberOfBranches"
        };
        DataFrame df = DataFrame.of(new double[][]{features}, columnNames);
        int[] predictions = codeQualityModel.predict(df);
        if (predictions[0] == 0) {  // Assuming 0 represents "bad" quality and 1 represents "good" quality
            logger.info("Potential code quality issue detected in method: " + methodSignature);
            suggestImprovements(methodSignature, metrics);
        }
    }

    private static void generateSequenceDiagram() {
        StringBuilder diagramBuilder = new StringBuilder();
        diagramBuilder.append("@startuml\n");
        diagramBuilder.append("skinparam sequence {\n")
                .append("    ParticipantPadding 20\n")
                .append("    BoxPadding 10\n")
                .append("    MessageAlign center\n")
                .append("    MaxMessageSize 100\n")
                .append("    WrapWidth 200\n")
                .append("}\n\n");

        // Define participants
        Set<String> definedParticipants = new HashSet<>();
        Map<String, String> participantAliases = new HashMap<>();
        int aliasCounter = 0;

        for (Project project : projects.values()) {
            diagramBuilder.append("box \"").append(project.name).append("\"\n");
            for (Map.Entry<String, List<MethodCall>> entry : project.methodCalls.entrySet()) {
                String caller = entry.getKey();
                defineParticipant(diagramBuilder, definedParticipants, participantAliases, caller, project.name, aliasCounter++);

                for (MethodCall callee : entry.getValue()) {
                    defineParticipant(diagramBuilder, definedParticipants, participantAliases, callee.className, project.name, aliasCounter++);
                }
            }
            diagramBuilder.append("end box\n");
        }
        diagramBuilder.append("\n");

        // Generate sequence
        int groupCounter = 0;
        for (Project project : projects.values()) {
            for (Map.Entry<String, List<MethodCall>> entry : project.methodCalls.entrySet()) {
                String caller = entry.getKey();
                List<MethodCall> callees = entry.getValue();

                String groupName = "group_" + (groupCounter++);
                diagramBuilder.append("group ").append(getMethodName(caller)).append(" as ").append(groupName).append("\n");

                for (MethodCall callee : callees) {
                    appendMethodCall(diagramBuilder, caller, callee, project.name, participantAliases);
                }

                diagramBuilder.append("end\n\n");
            }
        }

        diagramBuilder.append("@enduml");

        String diagramContent = diagramBuilder.toString();

        try {
            generatePUML(diagramContent, "sequence_diagram.puml");
            logger.info("Sequence diagram generated in PUML format");
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
            diagramBuilder.append("participant \"").append(className).append("\" as ")
                    .append(alias).append(" <<").append(stereotype).append(">> ")
                    .append("#").append(getColorForProject(projectName)).append("\n");
            definedParticipants.add(participantName);
            participantAliases.put(participantName, alias);
        }
    }

    private static void appendMethodCall(StringBuilder diagramBuilder, String caller, MethodCall callee,
                                         String projectName, Map<String, String> participantAliases) {
        String callerAlias = participantAliases.get(sanitizeParticipantName(caller));
        String calleeAlias = participantAliases.get(sanitizeParticipantName(callee.className));

        diagramBuilder.append(callerAlias)
                .append(" -> ")
                .append(calleeAlias)
                .append(": ")
                .append(truncateMessage(getMethodName(callee.methodSignature)))
                .append("\n");

        if (callee.methodBody != null && !callee.methodBody.isEmpty()) {
            appendMethodBodyDetails(diagramBuilder, callee, calleeAlias, projectName, participantAliases);
        }

        diagramBuilder.append(calleeAlias)
                .append(" --> ")
                .append(callerAlias)
                .append(": return\n");
    }

    private static void appendMethodBodyDetails(StringBuilder diagramBuilder, MethodCall callee,
                                                String calleeAlias, String projectName,
                                                Map<String, String> participantAliases) {
        String[] lines = callee.methodBody.split("\n");
        boolean inIfBlock = false;
        boolean inElseBlock = false;
        int nestingLevel = 0;

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            if (line.startsWith("if ") || line.startsWith("for ") || line.startsWith("while ")) {
                diagramBuilder.append("activate ").append(calleeAlias).append("\n");
                nestingLevel++;
            } else if (line.equals("}")) {
                if (nestingLevel > 0) {
                    nestingLevel--;
                    diagramBuilder.append("deactivate ").append(calleeAlias).append("\n");
                }
                if (inIfBlock || inElseBlock) {
                    diagramBuilder.append("end\n");
                    inIfBlock = false;
                    inElseBlock = false;
                }
            }

            if (line.startsWith("if ")) {
                if (inIfBlock) diagramBuilder.append("end\n");
                if (inElseBlock) diagramBuilder.append("end\n");
                diagramBuilder.append("alt ").append(truncateMessage(line)).append("\n");
                inIfBlock = true;
                inElseBlock = false;
            } else if (line.startsWith("else ")) {
                if (inIfBlock) diagramBuilder.append("else ").append(truncateMessage(line)).append("\n");
                inElseBlock = true;
            } else if (line.contains("repository.") || line.contains("Repository.")) {
                diagramBuilder.append(calleeAlias).append(" -> Database: ").append(truncateMessage(line)).append("\n");
                diagramBuilder.append("Database --> ").append(calleeAlias).append(": return\n");
            } else if (line.contains("restTemplate.") || line.contains("webClient.")) {
                String apiName = extractApiName(line);
                diagramBuilder.append(calleeAlias).append(" -> ").append(apiName).append(": API Call\n");
                diagramBuilder.append(apiName).append(" --> ").append(calleeAlias).append(": response\n");
            } else if (line.contains("new ") && line.contains("Service")) {
                String serviceName = extractServiceName(line);
                String serviceAlias = participantAliases.get(sanitizeParticipantName(serviceName));
                if (serviceAlias == null) {
                    serviceAlias = "S" + participantAliases.size();
                    participantAliases.put(sanitizeParticipantName(serviceName), serviceAlias);
                    diagramBuilder.append("participant \"").append(serviceName).append("\" as ")
                            .append(serviceAlias).append(" <<Service>> #")
                            .append(getColorForProject(projectName)).append("\n");
                }
                diagramBuilder.append(calleeAlias).append(" -> ").append(serviceAlias).append(": create\n");
            }
        }

        while (nestingLevel > 0) {
            diagramBuilder.append("deactivate ").append(calleeAlias).append("\n");
            nestingLevel--;
        }

        if (inIfBlock || inElseBlock) {
            diagramBuilder.append("end\n");
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
        // This is a simplified version. You might want to enhance it based on your specific API naming conventions.
        if (line.contains("restTemplate")) return "REST_API";
        if (line.contains("webClient")) return "WebClient_API";
        return "External_API";
    }

    private static String extractServiceName(String line) {
        // This is a simplified version. You might want to enhance it based on your specific service naming conventions.
        Pattern pattern = Pattern.compile("new\\s+(\\w+Service)");
        Matcher matcher = pattern.matcher(line);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "UnknownService";
    }

    private static String sanitizeParticipantName(String name) {
        return name.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private static void generateDependencyGraph() {
        StringBuilder graphBuilder = new StringBuilder();
        graphBuilder.append("@startuml\n");
        graphBuilder.append("skinparam rectangle {\n  BackgroundColor<<API>> LightBlue\n  BorderColor<<API>> Blue\n}\n");

        for (Project project : projects.values()) {
            graphBuilder.append("rectangle \"").append(project.name).append("\" {\n");
            for (String api : project.exposedAPIs) {
                graphBuilder.append("  rectangle \"").append(api).append("\"<<API>>\n");
            }
            graphBuilder.append("}\n");
        }

        for (Project callerProject : projects.values()) {
            for (List<MethodCall> calls : callerProject.methodCalls.values()) {
                for (MethodCall call : calls) {
                    for (Project calleeProject : projects.values()) {
                        if (calleeProject != callerProject && calleeProject.exposedAPIs.contains(call.methodSignature)) {
                            graphBuilder.append(callerProject.name)
                                    .append(" --> ")
                                    .append(calleeProject.name)
                                    .append(" : uses\n");
                        }
                    }
                }
            }
        }

        graphBuilder.append("@enduml");

        try {
            generatePUML(graphBuilder.toString(), "dependency_graph.puml");
            logger.info("Dependency graph generated: dependency_graph.puml");
        } catch (IOException e) {
            logger.severe("Error generating dependency graph: " + e.getMessage());
        }
    }

    private static void generatePUML(String content, String filename) throws IOException {
        try (FileWriter writer = new FileWriter(filename)) {
            writer.write(content);
        }
    }

    private static void generatePNG(String content) throws IOException {
        SourceStringReader reader = new SourceStringReader(content);
        try (FileOutputStream output = new FileOutputStream("sequence_diagram.png")) {
            reader.outputImage(output, new FileFormatOption(FileFormat.PNG));
        }
    }

    private static void generateSVG(String content) throws IOException {
        SourceStringReader reader = new SourceStringReader(content);
        try (FileOutputStream output = new FileOutputStream("sequence_diagram.svg")) {
            reader.outputImage(output, new FileFormatOption(FileFormat.SVG));
        }
    }

    private static String getClassName(String fullSignature) {
        return fullSignature.substring(0, fullSignature.lastIndexOf('.'));
    }

    private static String getMethodName(String fullSignature) {
        return fullSignature.substring(fullSignature.lastIndexOf('.') + 1);
    }

    private static MethodDeclaration getMethodDeclaration(Project project, String methodSignature) {
        // This is a placeholder. In a real implementation, you'd need to parse the source file and find the method.
        return null;
    }

    private static int calculateDepthOfInheritance(Project project, String methodSignature) {
        // This is a placeholder. In a real implementation, you'd need to analyze the class hierarchy.
        return 0;
    }

    // Add this method to handle user input for cancellation
    private static void listenForCancellation() {
        new Thread(() -> {
            Scanner scanner = new Scanner(System.in);
            while (!cancelled) {
                if (scanner.nextLine().equalsIgnoreCase("cancel")) {
                    cancelled = true;
                    System.out.println("\nCancellation requested. Stopping analysis...");
                    break;
                }
            }
        }).start();
    }
}