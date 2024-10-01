// package com.example;

// import org.objectweb.asm.ClassReader;
// import org.objectweb.asm.Type;
// import org.objectweb.asm.tree.*;
// import org.slf4j.Logger;
// import org.slf4j.LoggerFactory;

// import java.util.*;
// import java.util.regex.Matcher;
// import java.util.regex.Pattern;
// import java.util.stream.Collectors;

// public class APIInventoryExtractor {
// private static final Logger logger =
// LoggerFactory.getLogger(APIInventoryExtractor.class);
// private final Map<String, String> configProperties;
// private final String basePath;
// private final Set<String> enabledEndpoints;
// private final Set<String> ignoredPaths;
// private final Map<String, Set<String>> serviceMethodCalls;
// private final Map<String, String> controllerToServiceMap;
// private final String fileName;

// public APIInventoryExtractor(Map<String, String> configProperties, String
// fileName) {
// this.configProperties = configProperties;
// String port = configProperties.getOrDefault("server.port", "8080");
// this.basePath = "http://" +
// configProperties.getOrDefault("spring.application.name", "localhost") + ":" +
// port;
// this.enabledEndpoints = parseCommaSeparatedConfig("api.enabled-endpoints");
// this.ignoredPaths = parseCommaSeparatedConfig("api.ignored-paths");
// this.fileName = fileName;
// this.serviceMethodCalls = new HashMap<>();
// this.controllerToServiceMap = new HashMap<>();
// logger.info("APIInventoryExtractor initialized with base path: {}",
// basePath);
// }

// public APIInfo extractExposedAPIs(List<ClassNode> allClasses) {
// logger.info("Extracting exposed APIs");
// APIInfo apiInfo = new
// APIInfo(configProperties.getOrDefault("spring.application.name",
// "UnknownService"));

// List<APIInfo.ExposedAPI> exposedApis = allClasses.stream()
// .filter(this::isApiClass)
// .flatMap(classNode -> extractAPIsFromClass(classNode, allClasses).stream())
// .filter(this::isApiEnabled)
// .collect(Collectors.toList());

// apiInfo.setExposedApis(exposedApis);

// buildServiceMethodCallMap(allClasses);
// mapApisToServices(apiInfo, allClasses);
// identifyAsyncApis(apiInfo);
// extractDependencies(apiInfo, allClasses);
// extractExternalAPICalls(apiInfo, allClasses);

// logger.info("Extracted {} APIs", exposedApis.size());
// return apiInfo;
// }

// private boolean isApiClass(ClassNode classNode) {
// return isSpringController(classNode) ||
// isQuarkusResource(classNode) ||
// isMicronautController(classNode) ||
// isVertxVerticle(classNode);
// }

// private boolean isSpringController(ClassNode classNode) {
// return hasAnnotation(classNode, "RestController") ||
// hasAnnotation(classNode, "Controller");
// }

// private boolean isQuarkusResource(ClassNode classNode) {
// return hasAnnotation(classNode, "Path");
// }

// private boolean isMicronautController(ClassNode classNode) {
// return hasAnnotation(classNode, "Controller");
// }

// private boolean isVertxVerticle(ClassNode classNode) {
// return classNode.superName != null &&
// classNode.superName.contains("AbstractVerticle");
// }

// private List<APIInfo.ExposedAPI> extractAPIsFromClass(ClassNode classNode,
// List<ClassNode> allClasses) {
// List<APIInfo.ExposedAPI> apis = new ArrayList<>();
// String classPath = extractClassPath(classNode);
// String apiVersion = extractApiVersion(classNode);

// for (MethodNode methodNode : classNode.methods) {
// APIInfo.ExposedAPI api = extractAPIInfo(classNode, methodNode, classPath,
// apiVersion, allClasses);
// if (api != null) {
// apis.add(api);
// }
// }

// return apis;
// }

// private APIInfo.ExposedAPI extractAPIInfo(ClassNode classNode, MethodNode
// methodNode, String classPath,
// String apiVersion, List<ClassNode> allClasses) {
// String httpMethod = extractHttpMethod(methodNode);
// String methodPath = extractMethodPath(methodNode);

// if (httpMethod == null || methodPath == null) {
// return null;
// }

// String fullPath = combinePaths(apiVersion, classPath, methodPath);
// String serviceMethod = methodNode.name;
// List<APIInfo.ParameterInfo> parameters = extractParameters(methodNode);
// String returnType = extractReturnType(methodNode);
// String controllerClassName = classNode.name;
// String serviceClassName = extractServiceClassName(classNode);
// boolean isAsync = isAsyncMethod(methodNode);
// List<APIInfo.ExternalAPI> externalAPIs = extractExternalAPICalls(classNode,
// methodNode, allClasses);

// return new APIInfo.ExposedAPI(
// fullPath,
// httpMethod,
// serviceMethod,
// parameters,
// returnType,
// controllerClassName,
// serviceClassName,
// isAsync,
// externalAPIs);
// }

// private String extractApiVersion(ClassNode classNode) {
// if (classNode.visibleAnnotations != null) {
// for (AnnotationNode an : classNode.visibleAnnotations) {
// if (an.desc.contains("ApiVersion")) {
// return extractAnnotationValue(an, "value");
// }
// }
// }

// String className = classNode.name;
// Pattern versionPattern = Pattern.compile("v\\d+");
// Matcher matcher = versionPattern.matcher(className);
// if (matcher.find()) {
// return matcher.group();
// }

// return null;
// }

// private void mapApisToServices(APIInfo apiInfo, List<ClassNode> allClasses) {
// Map<String, ClassNode> serviceClasses = allClasses.stream()
// .filter(this::isService)
// .collect(Collectors.toMap(cn -> cn.name, cn -> cn));

// for (APIInfo.ExposedAPI api : apiInfo.getExposedApis()) {
// String serviceClassName = findServiceForApi(api, serviceClasses);
// api.setServiceClassName(serviceClassName);
// if (serviceClassName != null) {
// controllerToServiceMap.put(api.getControllerClassName(), serviceClassName);
// }
// }
// }

// private String findServiceForApi(APIInfo.ExposedAPI api, Map<String,
// ClassNode> serviceClasses) {
// String controllerName = api.getControllerClassName();
// String potentialServiceName = controllerName.replace("Controller",
// "Service");
// if (serviceClasses.containsKey(potentialServiceName)) {
// return potentialServiceName;
// }

// ClassNode controllerNode = serviceClasses.get(controllerName);
// if (controllerNode != null && controllerNode.fields != null) {
// for (FieldNode field : controllerNode.fields) {
// if (hasAnnotation(field, "Autowired") || hasAnnotation(field, "Inject")) {
// String fieldType = Type.getType(field.desc).getClassName();
// if (serviceClasses.containsKey(fieldType)) {
// return fieldType;
// }
// }
// }
// }

// return null;
// }

// private void identifyAsyncApis(APIInfo apiInfo) {
// for (APIInfo.ExposedAPI api : apiInfo.getExposedApis()) {
// boolean isAsync = isAsyncMethod(api.getServiceMethod()) ||
// hasReactiveReturnType(api.getReturnType()) ||
// isMessageDriven(api);
// api.setAsync(isAsync);
// }
// }

// private boolean isAsyncMethod(String methodName) {
// return methodName.contains("Async") ||
// hasAnnotation(methodName, "Async");
// }

// private boolean hasReactiveReturnType(String returnType) {
// return returnType.contains("Mono") || returnType.contains("Flux");
// }

// private boolean isMessageDriven(APIInfo.ExposedAPI api) {
// return hasAnnotation(api.getServiceMethod(), "KafkaListener") ||
// hasAnnotation(api.getServiceMethod(), "RabbitListener") ||
// api.getServiceMethod().contains("consume") ||
// api.getServiceMethod().contains("process");
// }

// private void extractExternalAPICalls(APIInfo apiInfo, List<ClassNode>
// allClasses) {
// for (APIInfo.ExposedAPI api : apiInfo.getExposedApis()) {
// List<APIInfo.ExternalAPI> externalAPIs =
// extractExternalAPICalls(api.getControllerClassName(),
// api.getServiceMethod(), allClasses);
// api.setExternalAPIs(externalAPIs);
// }
// }

// private List<APIInfo.ExternalAPI> extractExternalAPICalls(String
// controllerClassName, String serviceMethod,
// List<ClassNode> allClasses) {
// List<APIInfo.ExternalAPI> externalAPIs = new ArrayList<>();
// ClassNode controllerClass = allClasses.stream()
// .filter(cn -> cn.name.equals(controllerClassName))
// .findFirst()
// .orElse(null);

// if (controllerClass != null) {
// MethodNode methodNode = controllerClass.methods.stream()
// .filter(mn -> mn.name.equals(serviceMethod))
// .findFirst()
// .orElse(null);

// if (methodNode != null) {
// externalAPIs.addAll(extractHttpClientCalls(methodNode));
// externalAPIs.addAll(extractFeignClientCalls(methodNode, allClasses));
// externalAPIs.addAll(extractMessageBrokerCalls(methodNode));
// }
// }

// return externalAPIs;
// }

// private List<APIInfo.ExternalAPI> extractHttpClientCalls(MethodNode
// methodNode) {
// List<APIInfo.ExternalAPI> externalAPIs = new ArrayList<>();
// for (AbstractInsnNode insn : methodNode.instructions) {
// if (insn instanceof MethodInsnNode) {
// MethodInsnNode methodInsn = (MethodInsnNode) insn;
// if (isHttpClientMethod(methodInsn)) {
// String url = extractUrlFromHttpClientCall(methodInsn);
// String httpMethod = extractHttpMethodFromHttpClientCall(methodInsn);
// if (url != null && httpMethod != null) {
// externalAPIs.add(new APIInfo.ExternalAPI(url, httpMethod, methodNode.name));
// }
// }
// }
// }
// return externalAPIs;
// }

// private List<APIInfo.ExternalAPI> extractFeignClientCalls(MethodNode
// methodNode, List<ClassNode> allClasses) {
// List<APIInfo.ExternalAPI> externalAPIs = new ArrayList<>();
// for (AbstractInsnNode insn : methodNode.instructions) {
// if (insn instanceof MethodInsnNode) {
// MethodInsnNode methodInsn = (MethodInsnNode) insn;
// ClassNode feignClientClass = findFeignClientClass(methodInsn.owner,
// allClasses);
// if (feignClientClass != null) {
// String baseUrl = extractFeignClientBaseUrl(feignClientClass);
// MethodNode feignMethod = findMethodInClass(feignClientClass,
// methodInsn.name);
// if (feignMethod != null) {
// String path = extractFeignMethodPath(feignMethod);
// String httpMethod = extractFeignMethodHttpMethod(feignMethod);
// if (baseUrl != null && path != null && httpMethod != null) {
// externalAPIs.add(new APIInfo.ExternalAPI(baseUrl + path, httpMethod,
// methodNode.name));
// }
// }
// }
// }
// }
// return externalAPIs;
// }

// private List<APIInfo.ExternalAPI> extractMessageBrokerCalls(MethodNode
// methodNode) {
// List<APIInfo.ExternalAPI> externalAPIs = new ArrayList<>();
// if (methodNode.visibleAnnotations != null) {
// for (AnnotationNode an : methodNode.visibleAnnotations) {
// if (an.desc.contains("KafkaListener")) {
// String topic = extractAnnotationValue(an, "topics");
// if (topic != null) {
// externalAPIs.add(new APIInfo.ExternalAPI("kafka://" + topic, "CONSUME",
// methodNode.name));
// }
// } else if (an.desc.contains("RabbitListener")) {
// String queue = extractAnnotationValue(an, "queues");
// if (queue != null) {
// externalAPIs.add(new APIInfo.ExternalAPI("rabbitmq://" + queue, "CONSUME",
// methodNode.name));
// }
// }
// }
// }
// return externalAPIs;
// }

// private boolean isHttpClientMethod(MethodInsnNode methodInsn) {
// String owner = methodInsn.owner;
// String name = methodInsn.name;
// return (owner.contains("RestTemplate")
// && (name.equals("getForObject") || name.equals("postForEntity") ||
// name.equals("exchange"))) ||
// (owner.contains("WebClient")
// && (name.equals("get") || name.equals("post") || name.equals("put") ||
// name.equals("delete")))
// ||
// (owner.contains("HttpClient") && (name.equals("send") ||
// name.equals("sendAsync")));
// }

// private String extractUrlFromHttpClientCall(MethodInsnNode methodInsn) {
// // This is a simplified implementation. In a real-world scenario, you'd need
// to
// // analyze the bytecode more thoroughly to extract the URL.
// return "http://example.com/api"; // Placeholder
// }

// private String extractHttpMethodFromHttpClientCall(MethodInsnNode methodInsn)
// {
// String name = methodInsn.name.toLowerCase();
// if (name.contains("get"))
// return "GET";
// if (name.contains("post"))
// return "POST";
// if (name.contains("put"))
// return "PUT";
// if (name.contains("delete"))
// return "DELETE";
// if (name.contains("patch"))
// return "PATCH";
// return "UNKNOWN";
// }

// private ClassNode findFeignClientClass(String className, List<ClassNode>
// allClasses) {
// return allClasses.stream()
// .filter(cn -> cn.name.equals(className) && hasFeignClientAnnotation(cn))
// .findFirst()
// .orElse(null);
// }

// private boolean hasFeignClientAnnotation(ClassNode classNode) {
// return classNode.visibleAnnotations != null &&
// classNode.visibleAnnotations.stream()
// .anyMatch(an -> an.desc.contains("FeignClient"));
// }

// private String extractFeignClientBaseUrl(ClassNode feignClientClass) {
// if (feignClientClass.visibleAnnotations != null) {
// for (AnnotationNode an : feignClientClass.visibleAnnotations) {
// if (an.desc.contains("FeignClient")) {
// return extractAnnotationValue(an, "url");
// }
// }
// }
// return null;
// }

// private MethodNode findMethodInClass(ClassNode classNode, String methodName)
// {
// return classNode.methods.stream()
// .filter(mn -> mn.name.equals(methodName))
// .findFirst()
// .orElse(null);
// }

// private String extractFeignMethodPath(MethodNode methodNode) {
// if (methodNode.visibleAnnotations != null) {
// for (AnnotationNode an : methodNode.visibleAnnotations) {
// if (an.desc.contains("RequestMapping")) {
// return extractAnnotationValue(an, "value");
// }
// }
// }
// return null;
// }

// private String extractFeignMethodHttpMethod(MethodNode methodNode) {
// if (methodNode.visibleAnnotations != null) {
// for (AnnotationNode an : methodNode.visibleAnnotations) {
// if (an.desc.contains("GetMapping"))
// return "GET";
// if (an.desc.contains("PostMapping"))
// return "POST";
// if (an.desc.contains("PutMapping"))
// return "PUT";
// if (an.desc.contains("DeleteMapping"))
// return "DELETE";
// if (an.desc.contains("PatchMapping"))
// return "PATCH";
// }
// }
// return "UNKNOWN";
// }

// private void buildServiceMethodCallMap(List<ClassNode> allClasses) {
// for (ClassNode classNode : allClasses) {
// if (isController(classNode)) {
// for (MethodNode methodNode : classNode.methods) {
// Set<String> calledMethods = findCalledServiceMethods(methodNode, allClasses);
// serviceMethodCalls.put(classNode.name + "." + methodNode.name,
// calledMethods);
// }
// }
// }
// }

// private Set<String> findCalledServiceMethods(MethodNode methodNode,
// List<ClassNode> allClasses) {
// Set<String> calledMethods = new HashSet<>();
// for (AbstractInsnNode insn : methodNode.instructions) {
// if (insn instanceof MethodInsnNode) {
// MethodInsnNode methodInsn = (MethodInsnNode) insn;
// if (isServiceMethod(methodInsn, allClasses)) {
// calledMethods.add(methodInsn.owner + "." + methodInsn.name);
// }
// }
// }
// return calledMethods;
// }

// private boolean isServiceMethod(MethodInsnNode methodInsn, List<ClassNode>
// allClasses) {
// ClassNode targetClass = allClasses.stream()
// .filter(cn -> cn.name.equals(methodInsn.owner))
// .findFirst()
// .orElse(null);
// return targetClass != null && isService(targetClass);
// }

// private void extractDependencies(APIInfo apiInfo, List<ClassNode> allClasses)
// {
// Set<String> dependencies = new HashSet<>();
// for (APIInfo.ExposedAPI api : apiInfo.getExposedApis()) {
// String controllerName = api.getControllerClassName();
// String methodName = api.getServiceMethod();
// Set<String> calledMethods = serviceMethodCalls.get(controllerName + "." +
// methodName);
// if (calledMethods != null) {
// for (String calledMethod : calledMethods) {
// String calledServiceName = calledMethod.substring(0,
// calledMethod.lastIndexOf('.'));
// dependencies.add(calledServiceName);
// addTransitiveDependencies(dependencies, calledServiceName, allClasses);
// }
// }
// }
// apiInfo.setDependencies(new ArrayList<>(dependencies));
// }

// private void addTransitiveDependencies(Set<String> dependencies, String
// serviceName, List<ClassNode> allClasses) {
// ClassNode serviceNode = allClasses.stream()
// .filter(cn -> cn.name.equals(serviceName))
// .findFirst()
// .orElse(null);
// if (serviceNode != null) {
// for (MethodNode methodNode : serviceNode.methods) {
// for (AbstractInsnNode insn : methodNode.instructions) {
// if (insn instanceof MethodInsnNode) {
// MethodInsnNode methodInsn = (MethodInsnNode) insn;
// if (isServiceMethod(methodInsn, allClasses)) {
// String calledService = methodInsn.owner;
// if (dependencies.add(calledService)) {
// addTransitiveDependencies(dependencies, calledService, allClasses);
// }
// }
// }
// }
// }
// }
// }

// private boolean isApiEnabled(APIInfo.ExposedAPI apiInfo) {
// String path = apiInfo.getPath();
// if (apiInfo.getHttpMethod().equals("UNKNOWN"))
// return false;

// if (isPathIgnored(path)) {
// logger.debug("API {} is ignored by configuration", path);
// return false;
// }

// if (isPathEnabled(path)) {
// logger.debug("API {} is enabled", path);
// return true;
// }

// logger.debug("API {} is not enabled by configuration", path);
// return false;
// }

// private boolean isPathIgnored(String path) {
// return ignoredPaths.stream()
// .anyMatch(ignoredPath -> path.startsWith(ignoredPath));
// }

// private boolean isPathEnabled(String path) {
// if (path.isEmpty()) {
// return false;
// }
// if (enabledEndpoints.isEmpty()) {
// return true;
// }

// return enabledEndpoints.stream()
// .anyMatch(enabledPath -> path.startsWith(enabledPath));
// }

// private Set<String> parseCommaSeparatedConfig(String key) {
// return Arrays.stream(configProperties.getOrDefault(key, "").split(","))
// .map(String::trim)
// .filter(s -> !s.isEmpty())
// .collect(Collectors.toSet());
// }

// private String combinePaths(String... paths) {
// return Arrays.stream(paths)
// .filter(path -> path != null && !path.isEmpty())
// .collect(Collectors.joining("/"))
// .replaceAll("//+", "/");
// }

// private boolean isController(ClassNode classNode) {
// return hasAnnotation(classNode, "RestController") || hasAnnotation(classNode,
// "Controller")
// || hasAnnotation(classNode, "Path");
// }

// private boolean isService(ClassNode classNode) {
// return hasAnnotation(classNode, "Service") || hasAnnotation(classNode,
// "Component")
// || hasAnnotation(classNode, "Repository");
// }

// private boolean hasAnnotation(ClassNode classNode, String annotationName) {
// return classNode.visibleAnnotations != null &&
// classNode.visibleAnnotations.stream()
// .anyMatch(an -> an.desc.contains(annotationName));
// }

// private boolean hasAnnotation(MethodNode methodNode, String annotationName) {
// return methodNode.visibleAnnotations != null &&
// methodNode.visibleAnnotations.stream()
// .anyMatch(an -> an.desc.contains(annotationName));
// }

// private boolean hasAnnotation(FieldNode field, String annotationName) {
// return field.visibleAnnotations != null &&
// field.visibleAnnotations.stream()
// .anyMatch(an -> an.desc.contains(annotationName));
// }

// private String extractAnnotationValue(AnnotationNode an, String key) {
// if (an.values != null) {
// for (int i = 0; i < an.values.size(); i += 2) {
// if (an.values.get(i).equals(key)) {
// return an.values.get(i + 1).toString();
// }
// }
// }
// return null;
// }

// private String extractClassPath(ClassNode classNode) {
// return extractPathFromAnnotation(classNode.visibleAnnotations,
// "RequestMapping");
// }

// private String extractMethodPath(MethodNode methodNode) {
// return extractPathFromAnnotation(methodNode.visibleAnnotations, "Mapping");
// }

// private String extractPathFromAnnotation(List<AnnotationNode> annotations,
// String annotationName) {
// if (annotations == null)
// return null;
// for (AnnotationNode an : annotations) {
// if (an.desc.contains(annotationName)) {
// return extractAnnotationValue(an, "value");
// }
// }
// return null;
// }

// private String extractHttpMethod(MethodNode methodNode) {
// if (methodNode.visibleAnnotations != null) {
// for (AnnotationNode an : methodNode.visibleAnnotations) {
// String desc = an.desc.toLowerCase();
// if (desc.contains("getmapping"))
// return "GET";
// if (desc.contains("postmapping"))
// return "POST";
// if (desc.contains("putmapping"))
// return "PUT";
// if (desc.contains("deletemapping"))
// return "DELETE";
// if (desc.contains("patchmapping"))
// return "PATCH";
// if (desc.contains("requestmapping")) {
// return extractMethodFromRequestMapping(an);
// }
// }
// }
// return "UNKNOWN";
// }

// private String extractMethodFromRequestMapping(AnnotationNode an) {
// String method = extractAnnotationValue(an, "method");
// if (method != null) {
// if (method.contains("GET"))
// return "GET";
// if (method.contains("POST"))
// return "POST";
// if (method.contains("PUT"))
// return "PUT";
// if (method.contains("DELETE"))
// return "DELETE";
// if (method.contains("PATCH"))
// return "PATCH";
// }
// return "GET"; // Default to GET if method is not specified
// }

// private List<APIInfo.ParameterInfo> extractParameters(MethodNode methodNode)
// {
// List<APIInfo.ParameterInfo> parameters = new ArrayList<>();
// if (methodNode.visibleParameterAnnotations != null) {
// Type[] argumentTypes = Type.getArgumentTypes(methodNode.desc);
// for (int i = 0; i < methodNode.visibleParameterAnnotations.length; i++) {
// List<AnnotationNode> paramAnnotations =
// methodNode.visibleParameterAnnotations[i];
// if (paramAnnotations != null) {
// for (AnnotationNode an : paramAnnotations) {
// String paramType = argumentTypes[i].getClassName();
// String paramName = extractAnnotationValue(an, "value");
// String annotationType = extractParameterAnnotationType(an);
// if (paramName == null)
// paramName = "arg" + i;
// parameters.add(new APIInfo.ParameterInfo(paramType, paramName,
// annotationType));
// break;
// }
// }
// }
// }
// return parameters;
// }

// private String extractParameterAnnotationType(AnnotationNode an) {
// if (an.desc.contains("PathVariable"))
// return "PathVariable";
// if (an.desc.contains("RequestParam"))
// return "RequestParam";
// if (an.desc.contains("RequestBody"))
// return "RequestBody";
// if (an.desc.contains("RequestPart"))
// return "RequestPart";
// if (an.desc.contains("RequestHeader"))
// return "RequestHeader";
// return null;
// }

// private String extractReturnType(MethodNode methodNode) {
// if (methodNode.desc == null) {
// return "void";
// }

// Type returnType = Type.getReturnType(methodNode.desc);
// String typeName = returnType.getClassName();

// if (typeName.contains("ResponseEntity") || typeName.contains("Mono") ||
// typeName.contains("Flux")) {
// return extractGenericType(methodNode, typeName);
// }

// return typeName;
// }

// private String extractGenericType(MethodNode methodNode, String typeName) {
// if (methodNode.signature != null) {
// Type[] genericTypes = Type.getArgumentTypes(methodNode.signature);
// if (genericTypes != null && genericTypes.length > 0) {
// return typeName + "<" + genericTypes[0].getClassName() + ">";
// }
// }
// return typeName;
// }

// // Additional helper methods can be added here as needed
// }