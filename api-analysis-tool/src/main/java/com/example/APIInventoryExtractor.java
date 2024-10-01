package com.example;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.APIInfo.ExternalAPI;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class APIInventoryExtractor {
  private static final Logger logger = LoggerFactory.getLogger(APIInventoryExtractor.class);
  private final Map<String, String> configProperties;
  private final String basePath;
  private final Set<String> enabledEndpoints;
  private final Set<String> ignoredPaths;
  private final String projectName;
  private final List<ClassNode> allClasses;
  private final Map<String, Set<String>> serviceMethodCalls;

  public APIInventoryExtractor(Map<String, String> configProperties, String projectName, List<ClassNode> allClasses) {
    this.configProperties = configProperties;
    this.projectName = projectName;
    this.allClasses = allClasses;
    String port = configProperties.getOrDefault("server.port", "8080");
    this.basePath = "http://" + configProperties.getOrDefault("spring.application.name", "localhost") + ":" + port;
    this.enabledEndpoints = parseCommaSeparatedConfig("api.enabled-endpoints");
    this.ignoredPaths = parseCommaSeparatedConfig("api.ignored-paths");
    this.serviceMethodCalls = new HashMap<>();
    buildServiceMethodCallMap();
    logger.info("APIInventoryExtractor initialized with base path: {}", basePath);
  }

  private void buildServiceMethodCallMap() {
    for (ClassNode classNode : allClasses) {
      if (isController(classNode)) {
        for (MethodNode methodNode : classNode.methods) {
          Set<String> calledMethods = findCalledServiceMethods(methodNode);
          serviceMethodCalls.put(classNode.name + "." + methodNode.name, calledMethods);
        }
      }
    }
  }

  private Set<String> findCalledServiceMethods(MethodNode methodNode) {
    Set<String> calledMethods = new HashSet<>();
    for (AbstractInsnNode insn : methodNode.instructions) {
      if (insn instanceof MethodInsnNode) {
        MethodInsnNode methodInsn = (MethodInsnNode) insn;
        if (isServiceMethod(methodInsn)) {
          calledMethods.add(methodInsn.owner + "." + methodInsn.name);
        }
      }
    }
    return calledMethods;
  }

  private boolean isServiceMethod(MethodInsnNode methodInsn) {
    ClassNode targetClass = allClasses.stream()
        .filter(cn -> cn.name.equals(methodInsn.owner))
        .findFirst()
        .orElse(null);
    return targetClass != null && isService(targetClass);
  }

  public APIInfo extractExposedAPIs() {
    logger.info("Extracting exposed APIs for project: {}", projectName);
    APIInfo apiInfo = new APIInfo(projectName);
    String apiVersion = extractApiVersion(allClasses);
    List<APIInfo.ExposedAPI> exposedApis = new ArrayList<>();

    for (ClassNode classNode : allClasses) {
      if (isApiClass(classNode)) {
        exposedApis.addAll(extractAPIsFromClass(classNode, allClasses, apiVersion));
      }
    }

    apiInfo.setExposedApis(exposedApis);

    mapApisToServices(apiInfo, allClasses);
    identifyAsyncApis(apiInfo);
    extractDependencies(apiInfo, allClasses);
    extractExternalAPICalls(apiInfo, allClasses, configProperties);
    handleApiVersioning(apiInfo);

    logger.info("Extracted {} APIs", exposedApis.size());
    return apiInfo;
  }

  private void mapApisToServices(APIInfo apiInfo, List<ClassNode> allClasses) {
    Map<String, String> controllerToServiceMap = new HashMap<>();
    for (APIInfo.ExposedAPI api : apiInfo.getExposedApis()) {
      String serviceClassName = extractServiceClassName(api.getControllerClassName(), allClasses);
      controllerToServiceMap.put(api.getControllerClassName(), serviceClassName);
      api.setServiceClassName(serviceClassName);
    }
    apiInfo.setControllerToServiceMap(controllerToServiceMap);
  }

  private String extractServiceClassName(String controllerClassName, List<ClassNode> allClasses) {
    ClassNode classNode = allClasses.stream()
        .filter(cn -> cn.name.equals(controllerClassName))
        .findFirst()
        .orElse(null);

    if (classNode == null) {
      return null;
    }

    // First, try to find a field with @Autowired or @Inject annotation
    for (FieldNode field : classNode.fields) {
      if (hasAnnotation(field, "Autowired") || hasAnnotation(field, "Inject")) {
        return Type.getType(field.desc).getClassName();
      }
    }

    // If not found, try to infer from the class name
    String className = classNode.name.substring(classNode.name.lastIndexOf('/') + 1);
    if (className.endsWith("Controller")) {
      return className.substring(0, className.length() - "Controller".length()) + "Service";
    }

    // If still not found, return null
    return null;
  }

  private void identifyAsyncApis(APIInfo apiInfo) {
    for (APIInfo.ExposedAPI api : apiInfo.getExposedApis()) {
      boolean isAsync = isAsyncMethod(api) ||
          hasReactiveReturnType(api.getReturnType()) ||
          isMessagingMethod(api);
      api.setAsync(isAsync);
    }
  }

  private void handleApiVersioning(APIInfo apiInfo) {
    for (APIInfo.ExposedAPI api : apiInfo.getExposedApis()) {
      String path = api.getPath();
      if (path.matches("/v\\d+/.*")) {
        String[] parts = path.split("/");
        api.setVersion(parts[1]);
        api.setPath("/" + String.join("/", Arrays.copyOfRange(parts, 2, parts.length)));
      }
    }
  }

  private boolean isServiceMethod(MethodInsnNode methodInsn, List<ClassNode> allClasses) {
    ClassNode targetClass = allClasses.stream()
        .filter(cn -> cn.name.equals(methodInsn.owner))
        .findFirst()
        .orElse(null);
    return targetClass != null && isService(targetClass);
  }

  private void extractDependencies(APIInfo apiInfo, List<ClassNode> allClasses) {
    Set<String> dependencies = new HashSet<>();
    for (APIInfo.ExposedAPI api : apiInfo.getExposedApis()) {
      String controllerName = api.getControllerClassName();
      String methodName = api.getServiceMethod();
      Set<String> calledMethods = serviceMethodCalls.get(controllerName + "." + methodName);
      if (calledMethods != null) {
        for (String calledMethod : calledMethods) {
          String calledServiceName = calledMethod.substring(0, calledMethod.lastIndexOf('.'));
          dependencies.add(calledServiceName);
          addTransitiveDependencies(dependencies, calledServiceName, allClasses);
        }
      }
    }
    apiInfo.setDependencies(new ArrayList<>(dependencies));
  }

  private void addTransitiveDependencies(Set<String> dependencies, String serviceName, List<ClassNode> allClasses) {
    ClassNode serviceNode = allClasses.stream()
        .filter(cn -> cn.name.equals(serviceName))
        .findFirst()
        .orElse(null);
    if (serviceNode != null) {
      for (MethodNode methodNode : serviceNode.methods) {
        for (AbstractInsnNode insn : methodNode.instructions) {
          if (insn instanceof MethodInsnNode) {
            MethodInsnNode methodInsn = (MethodInsnNode) insn;
            if (isServiceMethod(methodInsn, allClasses)) {
              String calledService = methodInsn.owner;
              if (dependencies.add(calledService)) {
                addTransitiveDependencies(dependencies, calledService, allClasses);
              }
            }
          }
        }
      }
    }
  }

  private boolean isAsyncMethod(APIInfo.ExposedAPI api) {
    return api.isAsync() || // Check if it's already marked as async
        api.getServiceMethod().contains("Async") || // Check method name
        api.getReturnType().contains("CompletableFuture") ||
        api.getReturnType().contains("Mono") ||
        api.getReturnType().contains("Flux") ||
        isMessagingMethod(api);
  }

  private boolean isMessagingMethod(APIInfo.ExposedAPI api) {
    // Check for common messaging annotations in the method name or class name
    return api.getServiceMethod().contains("RabbitListener") ||
        api.getServiceMethod().contains("KafkaListener") ||
        api.getServiceMethod().contains("JmsListener") ||
        api.getControllerClassName().contains("Messaging");
  }

  private boolean isAsyncMethod(MethodNode methodNode) {
    return hasAnnotation(methodNode, "Async") ||
        methodNode.desc.contains("CompletableFuture") ||
        methodNode.desc.contains("Mono") ||
        methodNode.desc.contains("Flux") ||
        isMessagingMethod(methodNode);
  }

  private boolean isMessagingMethod(MethodNode methodNode) {
    return hasAnnotation(methodNode, "RabbitListener") ||
        hasAnnotation(methodNode, "KafkaListener") ||
        hasAnnotation(methodNode, "JmsListener");
  }

  private boolean hasReactiveReturnType(String returnType) {
    return returnType.contains("Mono") || returnType.contains("Flux");
  }

  private boolean isApiClass(ClassNode classNode) {
    return isSpringController(classNode) ||
        isQuarkusResource(classNode) ||
        isMicronautController(classNode) ||
        isVertxVerticle(classNode) ||
        isOpenAPIClass(classNode);
  }

  private boolean isSpringController(ClassNode classNode) {
    return hasAnnotation(classNode, "RestController") ||
        hasAnnotation(classNode, "Controller");
  }

  private boolean isQuarkusResource(ClassNode classNode) {
    return hasAnnotation(classNode, "Path");
  }

  private boolean isMicronautController(ClassNode classNode) {
    return hasAnnotation(classNode, "Controller");
  }

  private boolean isVertxVerticle(ClassNode classNode) {
    return classNode.superName != null && classNode.superName.contains("AbstractVerticle");
  }

  private boolean isOpenAPIClass(ClassNode classNode) {
    return hasAnnotation(classNode, "OpenAPIDefinition") ||
        classNode.name.endsWith("Api") ||
        classNode.name.endsWith("Resource");
  }

  private boolean hasOpenAPIAnnotations(ClassNode classNode) {
    if (classNode.visibleAnnotations != null) {
      for (AnnotationNode annotation : classNode.visibleAnnotations) {
        if (annotation.desc.contains("ApiOperation") ||
            annotation.desc.contains("ApiResponses") ||
            annotation.desc.contains("ApiModel") ||
            annotation.desc.contains("Tag")) {
          return true;
        }
      }
    }
    return false;
  }

  private boolean hasSpringWebAnnotations(ClassNode classNode) {
    if (classNode.visibleAnnotations != null) {
      for (AnnotationNode annotation : classNode.visibleAnnotations) {
        if (annotation.desc.contains("RestController") ||
            annotation.desc.contains("RequestMapping") ||
            annotation.desc.contains("GetMapping") ||
            annotation.desc.contains("PostMapping") ||
            annotation.desc.contains("PutMapping") ||
            annotation.desc.contains("DeleteMapping") ||
            annotation.desc.contains("PatchMapping")) {
          return true;
        }
      }
    }
    return false;
  }

  private List<APIInfo.ExposedAPI> extractAPIsFromClass(ClassNode classNode, List<ClassNode> allClasses,
      String apiVersion) {
    List<APIInfo.ExposedAPI> apis = new ArrayList<>();
    String classLevelPath = extractClassLevelPath(classNode);

    for (MethodNode methodNode : classNode.methods) {
      APIInfo.ExposedAPI api = extractAPIInfo(classNode, methodNode, classLevelPath, apiVersion, allClasses);
      if (api != null) {
        List<APIInfo.ExternalAPI> externalAPIs = extractExternalAPICalls(classNode, methodNode, allClasses);
        api.setExternalAPIs(externalAPIs);
        apis.add(api);
      }
    }

    return apis;
  }

  private APIInfo.ExposedAPI extractAPIInfo(ClassNode classNode, MethodNode methodNode, String classLevelPath,
      String apiVersion, List<ClassNode> allClasses) {
    String httpMethod = extractHttpMethod(methodNode);
    String methodPath = extractMethodPath(methodNode);
    String fullPath = combinePaths(apiVersion, classLevelPath, methodPath);
    String serviceMethod = methodNode.name;
    List<APIInfo.ParameterInfo> parameters = extractParameters(methodNode);
    String returnType = extractReturnType(methodNode);
    String controllerClassName = classNode.name;
    String serviceClassName = extractServiceClassName(classNode);
    boolean isAsync = isAsyncMethod(methodNode);
    List<APIInfo.ExternalAPI> externalAPIs = extractExternalAPICalls(classNode, methodNode, allClasses);

    return new APIInfo.ExposedAPI(
        fullPath,
        httpMethod,
        serviceMethod,
        parameters,
        returnType,
        controllerClassName,
        serviceClassName,
        isAsync,
        externalAPIs,
        apiVersion);
  }

  private String extractClassLevelPath(ClassNode classNode) {
    for (AnnotationNode an : classNode.visibleAnnotations) {
      if (an.desc.contains("RequestMapping")) {
        return extractAnnotationValue(an, "value");
      }
    }
    return "";
  }

  private String extractServiceClassName(ClassNode classNode) {
    // First, try to find a field with @Autowired or @Inject annotation
    for (FieldNode field : classNode.fields) {
      if (hasAnnotation(field, "Autowired") || hasAnnotation(field, "Inject")) {
        return Type.getType(field.desc).getClassName();
      }
    }

    // If not found, try to infer from the class name
    String className = classNode.name.substring(classNode.name.lastIndexOf('/') + 1);
    if (className.endsWith("Controller")) {
      return className.substring(0, className.length() - "Controller".length()) + "Service";
    }

    // If still not found, return null
    return null;
  }

  private Set<String> parseCommaSeparatedConfig(String key) {
    return Arrays.stream(configProperties.getOrDefault(key, "").split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .collect(Collectors.toSet());
  }

  private String combinePaths(String... paths) {
    return Arrays.stream(paths)
        .filter(path -> path != null && !path.isEmpty())
        .collect(Collectors.joining("/"))
        .replaceAll("//+", "/");
  }

  private String extractApiVersion(List<ClassNode> allClasses) {
    for (ClassNode classNode : allClasses) {
      if (hasAnnotation(classNode, "ApiVersion")) {
        return extractAnnotationValue(classNode, "ApiVersion", "value");
      }
    }
    return "";
  }

  private String extractAnnotationValue(ClassNode classNode, String annotationName, String key) {
    for (AnnotationNode an : classNode.visibleAnnotations) {
      if (an.desc.contains(annotationName)) {
        return extractAnnotationValue(an, key);
      }
    }
    return "";
  }

  private boolean isController(ClassNode classNode) {
    return hasAnnotation(classNode, "RestController") || hasAnnotation(classNode, "Controller");
  }

  private boolean isService(ClassNode classNode) {
    return hasAnnotation(classNode, "Service") || hasAnnotation(classNode, "Component")
        || hasAnnotation(classNode, "Repository");
  }

  private boolean hasAnnotation(ClassNode classNode, String annotationName) {
    return classNode.visibleAnnotations != null &&
        classNode.visibleAnnotations.stream()
            .anyMatch(an -> an.desc.contains(annotationName));
  }

  private boolean hasAnnotation(MethodNode methodNode, String annotationName) {
    return methodNode.visibleAnnotations != null &&
        methodNode.visibleAnnotations.stream()
            .anyMatch(an -> an.desc.contains(annotationName));
  }

  private boolean hasAnnotation(FieldNode field, String annotationName) {
    if (field.visibleAnnotations != null) {
      for (AnnotationNode annotation : field.visibleAnnotations) {
        if (annotation.desc.contains(annotationName)) {
          return true;
        }
      }
    }
    return false;
  }

  private boolean hasAnnotation(String className, String methodName, String annotationName) {
    ClassNode classNode = findClassNode(className);
    if (classNode != null) {
      for (MethodNode methodNode : classNode.methods) {
        if (methodNode.name.equals(methodName)) {
          return methodNode.visibleAnnotations != null &&
              methodNode.visibleAnnotations.stream()
                  .anyMatch(an -> an.desc.contains(annotationName));
        }
      }
    }
    return false;
  }

  String extractReturnType(MethodNode methodNode) {
    if (methodNode.desc == null) {
      return "void";
    }

    Type returnType = Type.getReturnType(methodNode.desc);
    String typeName = returnType.getClassName();

    if (typeName.contains("ResponseEntity") || typeName.contains("Mono") || typeName.contains("Flux")) {
      return extractGenericType(methodNode, typeName);
    }

    return typeName;
  }

  private String extractGenericType(MethodNode methodNode, String typeName) {
    if (methodNode.signature != null) {
      Type[] genericTypes = Type.getArgumentTypes(methodNode.signature);
      if (genericTypes != null && genericTypes.length > 0) {
        return typeName + "<" + genericTypes[0].getClassName() + ">";
      }
    }
    return typeName;
  }

  List<APIInfo.ParameterInfo> extractParameters(MethodNode methodNode) {
    if (methodNode.desc == null) {
      return Collections.emptyList();
    }

    Type[] argumentTypes = Type.getArgumentTypes(methodNode.desc);
    List<APIInfo.ParameterInfo> parameters = new ArrayList<>();

    // Try to get parameter names from debug information
    String[] parameterNames = extractParameterNames(methodNode);

    for (int i = 0; i < argumentTypes.length; i++) {
      String paramType = argumentTypes[i].getClassName();
      String paramName = (parameterNames != null && i < parameterNames.length) ? parameterNames[i] : "arg" + i;
      String annotationType = extractParameterAnnotationType(methodNode, i);
      parameters.add(new APIInfo.ParameterInfo(paramType, paramName, annotationType));
    }

    return parameters;
  }

  private String[] extractParameterNames(MethodNode methodNode) {
    Type[] argumentTypes = Type.getArgumentTypes(methodNode.desc);
    String[] parameterNames = new String[argumentTypes.length];

    // Check for parameter annotations
    if (methodNode.visibleParameterAnnotations != null) {
      for (int i = 0; i < methodNode.visibleParameterAnnotations.length; i++) {
        List<AnnotationNode> annotations = methodNode.visibleParameterAnnotations[i];
        if (annotations != null) {
          for (AnnotationNode an : annotations) {
            if (an.desc.contains("RequestParam") || an.desc.contains("PathVariable")
                || an.desc.contains("RequestBody")) {
              if (an.values != null) {
                for (int j = 0; j < an.values.size(); j += 2) {
                  if (an.values.get(j).equals("value") || an.values.get(j).equals("name")) {
                    parameterNames[i] = (String) an.values.get(j + 1);
                    break;
                  }
                }
              }
              // If no name is specified in the annotation, use the parameter type
              if (parameterNames[i] == null) {
                parameterNames[i] = argumentTypes[i].getClassName();
              }
              break;
            }
          }
        }
      }
    }

    // Fall back to local variable names if available
    if (methodNode.localVariables != null) {
      for (LocalVariableNode lvn : methodNode.localVariables) {
        if (lvn.index > 0 && lvn.index <= parameterNames.length) {
          if (parameterNames[lvn.index - 1] == null) {
            parameterNames[lvn.index - 1] = lvn.name;
          }
        }
      }
    }

    // If still null, use generic parameter names
    for (int i = 0; i < parameterNames.length; i++) {
      if (parameterNames[i] == null) {
        parameterNames[i] = "param" + (i + 1);
      }
    }

    return parameterNames;
  }

  private String extractParameterAnnotationType(MethodNode methodNode, int index) {
    if (methodNode.visibleParameterAnnotations != null &&
        index < methodNode.visibleParameterAnnotations.length &&
        methodNode.visibleParameterAnnotations[index] != null) {
      for (AnnotationNode an : methodNode.visibleParameterAnnotations[index]) {
        if (an.desc.contains("PathVariable"))
          return "PathVariable";
        if (an.desc.contains("RequestParam"))
          return "RequestParam";
        if (an.desc.contains("RequestBody"))
          return "RequestBody";
        if (an.desc.contains("RequestPart"))
          return "RequestPart";
        if (an.desc.contains("RequestHeader"))
          return "RequestHeader";
        if (an.desc.contains("CookieValue"))
          return "CookieValue";
        if (an.desc.contains("MatrixVariable"))
          return "MatrixVariable";

        // Add more annotation checks as needed
      }
    }
    return null;
  }

  private String extractHttpMethod(MethodNode methodNode) {
    if (methodNode.visibleAnnotations != null) {
      for (AnnotationNode an : methodNode.visibleAnnotations) {
        String desc = an.desc.toLowerCase();
        if (desc.contains("mapping")) {
          if (desc.contains("get"))
            return "GET";
          if (desc.contains("post"))
            return "POST";
          if (desc.contains("put"))
            return "PUT";
          if (desc.contains("delete"))
            return "DELETE";
          if (desc.contains("patch"))
            return "PATCH";
          if (desc.contains("head"))
            return "HEAD";
          if (desc.contains("options"))
            return "OPTIONS";
          if (desc.contains("trace"))
            return "TRACE";

          // Handle @RequestMapping
          if (desc.contains("requestmapping")) {
            return extractMethodFromRequestMapping(an);
          }
        }
      }
    }
    return "UNKNOWN";
  }

  private String extractMethodFromRequestMapping(AnnotationNode an) {
    if (an.values != null) {
      for (int i = 0; i < an.values.size(); i += 2) {
        if (an.values.get(i).equals("method")) {
          Object methodValue = an.values.get(i + 1);
          if (methodValue instanceof String[]) {
            String[] methods = (String[]) methodValue;
            if (methods.length > 0) {
              String fullMethodName = methods[0];
              String[] parts = fullMethodName.split("\\.");
              return parts[parts.length - 1].toUpperCase();
            }
          } else if (methodValue instanceof List) {
            List<String[]> methods = (List<String[]>) methodValue;
            if (!methods.isEmpty()) {
              return methods.get(0)[1].toUpperCase();
            }
          }
        }
      }
    }
    return "GET"; // Default to GET if method is not specified
  }

  private String extractPath(ClassNode classNode, MethodNode methodNode) {
    String classPath = extractClassPath(classNode);
    String methodPath = extractMethodPath(methodNode);
    return combinePaths(classPath, methodPath);
  }

  private String extractClassPath(ClassNode classNode) {
    return extractPathFromAnnotation(classNode.visibleAnnotations, "RequestMapping");
  }

  private String extractMethodPath(MethodNode methodNode) {
    return extractPathFromAnnotation(methodNode.visibleAnnotations, "Mapping");
  }

  private String extractPathFromAnnotation(List<AnnotationNode> annotations, String annotationName) {
    if (annotations == null)
      return null;
    for (AnnotationNode an : annotations) {
      if (an.desc.contains(annotationName)) {
        return extractPathValue(an);
      }
    }
    return null;
  }

  private String extractPathValue(AnnotationNode an) {
    if (an.values != null) {
      for (int i = 0; i < an.values.size(); i += 2) {
        if (an.values.get(i).equals("value") || an.values.get(i).equals("path")) {
          Object pathValue = an.values.get(i + 1);
          if (pathValue instanceof String) {
            return (String) pathValue;
          } else if (pathValue instanceof List) {
            List<?> pathList = (List<?>) pathValue;
            if (!pathList.isEmpty() && pathList.get(0) instanceof String) {
              return (String) pathList.get(0);
            }
          }
        }
      }
    }
    return null;
  }

  private String extractApiVersion(ClassNode classNode) {
    for (AnnotationNode an : classNode.visibleAnnotations) {
      if (an.desc.contains("ApiVersion")) {
        return extractAnnotationValue(an, "value");
      }
    }

    String className = classNode.name;
    Pattern versionPattern = Pattern.compile("v\\d+");
    Matcher matcher = versionPattern.matcher(className);
    if (matcher.find()) {
      return matcher.group();
    }

    return "";
  }

  private String extractAnnotationValue(AnnotationNode an, String key) {
    if (an.values != null) {
      for (int i = 0; i < an.values.size(); i += 2) {
        if (an.values.get(i).equals(key)) {
          return an.values.get(i + 1).toString();
        }
      }
    }
    return null;
  }

  private List<APIInfo.ExternalAPI> extractHttpClientCalls(String controllerClassName, String serviceMethod,
      List<ClassNode> allClasses, Map<String, String> configProperties) {
    List<APIInfo.ExternalAPI> externalAPIs = new ArrayList<>();
    ClassNode controllerClass = findClassNode(controllerClassName, allClasses);
    if (controllerClass != null) {
      MethodNode methodNode = findMethodNode(controllerClass, serviceMethod);
      if (methodNode != null) {
        for (AbstractInsnNode insn : methodNode.instructions) {
          if (insn instanceof MethodInsnNode) {
            MethodInsnNode methodInsn = (MethodInsnNode) insn;
            if (isHttpClientMethod(methodInsn)) {
              String url = extractUrlFromHttpClientCall(methodInsn, configProperties);
              String httpMethod = extractHttpMethodFromHttpClientCall(methodInsn);
              String targetService = extractTargetService(methodInsn, allClasses);
              boolean isAsync = isAsyncCall(methodInsn);
              String responseType = extractResponseType(methodInsn, methodNode);
              externalAPIs
                  .add(new APIInfo.ExternalAPI(url, httpMethod, serviceMethod, isAsync, targetService, responseType));
            }
          }
        }
      }
    }
    return externalAPIs;
  }

  private ClassNode findClassNode(String controllerClassName, List<ClassNode> allClasses) {

    return allClasses.stream()
        .filter(cn -> cn.name.equals(controllerClassName))
        .findFirst()
        .orElse(null);
  }

  private MethodNode findMethodNode(ClassNode classNode, String serviceMethod) {
    return classNode.methods.stream()
        .filter(mn -> mn.name.equals(serviceMethod))
        .findFirst()
        .orElse(null);
  }

  private boolean isHttpClientMethod(MethodInsnNode methodInsn) {
    return methodInsn.owner.contains("RestTemplate") ||
        methodInsn.owner.contains("WebClient") ||
        methodInsn.owner.contains("HttpClient") ||
        methodInsn.owner.contains("OkHttpClient");
  }

  private String extractUrlFromHttpClientCall(MethodInsnNode methodInsn, Map<String, String> configProperties) {
    String url = null;
    if (methodInsn.owner.contains("RestTemplate")) {
      url = extractUrlFromRestTemplateCall(methodInsn);
    } else if (methodInsn.owner.contains("WebClient")) {
      url = extractUrlFromWebClientCall(methodInsn);
    } else if (methodInsn.owner.contains("HttpClient")) {
      url = extractUrlFromHttpClientCall(methodInsn);
    }

    if (url != null) {
      // Resolve placeholders using configProperties
      for (Map.Entry<String, String> entry : configProperties.entrySet()) {
        url = url.replace("${" + entry.getKey() + "}", entry.getValue());
      }
    }
    return url;
  }

  private String extractUrlFromRestTemplateCall(MethodInsnNode methodInsn) {
    for (AbstractInsnNode insn = methodInsn.getPrevious(); insn != null; insn = insn.getPrevious()) {
      if (insn instanceof LdcInsnNode) {
        LdcInsnNode ldcInsn = (LdcInsnNode) insn;
        if (ldcInsn.cst instanceof String) {
          String potentialUrl = (String) ldcInsn.cst;
          if (potentialUrl.startsWith("http://") || potentialUrl.startsWith("https://")) {
            return potentialUrl;
          }
        }
      }
    }
    return null;
  }

  private String extractUrlFromWebClientCall(MethodInsnNode methodInsn) {
    for (AbstractInsnNode insn = methodInsn.getPrevious(); insn != null; insn = insn.getPrevious()) {
      if (insn instanceof MethodInsnNode) {
        MethodInsnNode prevMethodInsn = (MethodInsnNode) insn;
        if (prevMethodInsn.name.equals("uri")) {
          return extractStringArgument(prevMethodInsn);
        }
      }
    }
    return null;
  }

  private String extractUrlFromHttpClientCall(MethodInsnNode methodInsn) {
    for (AbstractInsnNode insn = methodInsn.getPrevious(); insn != null; insn = insn.getPrevious()) {
      if (insn instanceof MethodInsnNode) {
        MethodInsnNode prevMethodInsn = (MethodInsnNode) insn;
        if (prevMethodInsn.name.equals("create")) {
          return extractStringArgument(prevMethodInsn);
        }
      }
    }
    return null;
  }

  private String extractStringArgument(MethodInsnNode methodInsn) {
    for (AbstractInsnNode insn = methodInsn.getPrevious(); insn != null; insn = insn.getPrevious()) {
      if (insn instanceof LdcInsnNode) {
        LdcInsnNode ldcInsn = (LdcInsnNode) insn;
        if (ldcInsn.cst instanceof String) {
          return (String) ldcInsn.cst;
        }
      }
    }
    return null;
  }

  private String extractHttpMethodFromHttpClientCall(MethodInsnNode methodInsn) {
    String methodName = methodInsn.name.toLowerCase();
    if (methodName.contains("get"))
      return "GET";
    if (methodName.contains("post"))
      return "POST";
    if (methodName.contains("put"))
      return "PUT";
    if (methodName.contains("delete"))
      return "DELETE";
    if (methodName.contains("patch"))
      return "PATCH";
    if (methodName.equals("exchange") || methodName.equals("send") || methodName.equals("sendAsync")) {
      // For these methods, we need to check the method arguments to determine the
      // HTTP method
      return extractHttpMethodFromArguments(methodInsn);
    }
    return "UNKNOWN";
  }

  private String extractHttpMethodFromArguments(MethodInsnNode methodInsn) {
    for (AbstractInsnNode insn = methodInsn.getPrevious(); insn != null; insn = insn.getPrevious()) {
      if (insn instanceof FieldInsnNode) {
        FieldInsnNode fieldInsn = (FieldInsnNode) insn;
        if (fieldInsn.owner.contains("HttpMethod")) {
          return fieldInsn.name;
        }
      }
    }
    return "UNKNOWN";
  }

  private List<APIInfo.ExternalAPI> extractFeignClientCalls(String controllerClassName, String serviceMethod,
      List<ClassNode> allClasses) {
    List<APIInfo.ExternalAPI> externalAPIs = new ArrayList<>();
    ClassNode controllerClass = findClassNode(controllerClassName, allClasses);
    if (controllerClass == null)
      return externalAPIs;

    MethodNode methodNode = findMethodNode(controllerClass, serviceMethod);
    if (methodNode == null)
      return externalAPIs;

    for (AbstractInsnNode insn : methodNode.instructions) {
      if (insn instanceof MethodInsnNode) {
        MethodInsnNode methodInsn = (MethodInsnNode) insn;
        ClassNode feignClientClass = findFeignClientClass(methodInsn.owner, allClasses);
        if (feignClientClass != null) {
          String baseUrl = extractFeignClientBaseUrl(feignClientClass);
          MethodNode feignMethod = findMethodNode(feignClientClass, methodInsn.name, methodInsn.desc);
          if (feignMethod != null) {
            String httpMethod = extractFeignMethodHttpMethod(feignMethod);
            String path = extractFeignMethodPath(feignMethod);
            String fullUrl = baseUrl + path;
            externalAPIs.add(
                new APIInfo.ExternalAPI(fullUrl, httpMethod, methodInsn.name, false, feignClientClass.name, "Unknown"));
          }
        }
      }
    }
    return externalAPIs;
  }

  private ClassNode findFeignClientClass(String className, List<ClassNode> allClasses) {
    return allClasses.stream()
        .filter(cn -> cn.name.equals(className) && hasFeignClientAnnotation(cn))
        .findFirst()
        .orElse(null);
  }

  private boolean hasFeignClientAnnotation(ClassNode classNode) {
    return classNode.visibleAnnotations != null &&
        classNode.visibleAnnotations.stream()
            .anyMatch(an -> an.desc.contains("FeignClient"));
  }

  private String extractFeignClientBaseUrl(ClassNode feignClientClass) {
    if (feignClientClass.visibleAnnotations != null) {
      for (AnnotationNode an : feignClientClass.visibleAnnotations) {
        if (an.desc.contains("FeignClient")) {
          String url = extractAnnotationValue(an, "url");
          if (url != null) {
            return url;
          }
          String name = extractAnnotationValue(an, "name");
          if (name != null) {
            return "${" + name + "}";
          }
        }
      }
    }
    return "";
  }

  private String extractFeignMethodHttpMethod(MethodNode feignMethod) {
    if (feignMethod.visibleAnnotations != null) {
      for (AnnotationNode an : feignMethod.visibleAnnotations) {
        if (an.desc.contains("GetMapping"))
          return "GET";
        if (an.desc.contains("PostMapping"))
          return "POST";
        if (an.desc.contains("PutMapping"))
          return "PUT";
        if (an.desc.contains("DeleteMapping"))
          return "DELETE";
        if (an.desc.contains("PatchMapping"))
          return "PATCH";
        if (an.desc.contains("RequestMapping")) {
          String method = extractAnnotationValue(an, "method");
          if (method != null) {
            return method.toUpperCase();
          }
        }
      }
    }
    return "UNKNOWN";
  }

  private String extractFeignMethodPath(MethodNode feignMethod) {
    if (feignMethod.visibleAnnotations != null) {
      for (AnnotationNode an : feignMethod.visibleAnnotations) {
        if (an.desc.contains("RequestMapping") ||
            an.desc.contains("GetMapping") ||
            an.desc.contains("PostMapping") ||
            an.desc.contains("PutMapping") ||
            an.desc.contains("DeleteMapping") ||
            an.desc.contains("PatchMapping")) {
          return extractAnnotationValue(an, "value");
        }
      }
    }
    return "";
  }

  private List<APIInfo.ExternalAPI> extractMessageBrokerCalls(String controllerClassName, String serviceMethod,
      List<ClassNode> allClasses) {
    List<APIInfo.ExternalAPI> externalAPIs = new ArrayList<>();
    ClassNode controllerClass = findClassNode(controllerClassName, allClasses);
    if (controllerClass == null)
      return externalAPIs;

    MethodNode methodNode = findMethodNode(controllerClass, serviceMethod);
    if (methodNode == null)
      return externalAPIs;

    for (AbstractInsnNode insn : methodNode.instructions) {
      if (insn instanceof MethodInsnNode) {
        MethodInsnNode methodInsn = (MethodInsnNode) insn;
        if (isMessageBrokerMethod(methodInsn)) {
          String brokerType = extractBrokerType(methodInsn);
          String topic = extractTopic(methodInsn);
          externalAPIs.add(new APIInfo.ExternalAPI(topic, "PUBLISH", methodInsn.name, true, brokerType, "void"));
        }
      }
    }
    return externalAPIs;
  }

  private boolean isMessageBrokerMethod(MethodInsnNode methodInsn) {
    return methodInsn.owner.contains("KafkaTemplate") ||
        methodInsn.owner.contains("RabbitTemplate") ||
        methodInsn.owner.contains("JmsTemplate");
  }

  private String extractBrokerType(MethodInsnNode methodInsn) {
    if (methodInsn.owner.contains("KafkaTemplate"))
      return "Kafka";
    if (methodInsn.owner.contains("RabbitTemplate"))
      return "RabbitMQ";
    if (methodInsn.owner.contains("JmsTemplate"))
      return "JMS";
    return "Unknown";
  }

  private String extractTopic(MethodInsnNode methodInsn) {
    for (AbstractInsnNode insn = methodInsn.getPrevious(); insn != null; insn = insn.getPrevious()) {
      if (insn instanceof LdcInsnNode) {
        LdcInsnNode ldcInsn = (LdcInsnNode) insn;
        if (ldcInsn.cst instanceof String) {
          return (String) ldcInsn.cst;
        }
      }
    }
    return "Unknown";
  }

  private void extractExternalAPICalls(APIInfo apiInfo, List<ClassNode> allClasses,
      Map<String, String> configProperties) {
    for (APIInfo.ExposedAPI api : apiInfo.getExposedApis()) {
      List<APIInfo.ExternalAPI> externalAPIs = new ArrayList<>();
      externalAPIs.addAll(
          extractHttpClientCalls(api.getControllerClassName(), api.getServiceMethod(), allClasses, configProperties));
      externalAPIs.addAll(extractFeignClientCalls(api.getControllerClassName(), api.getServiceMethod(), allClasses));
      externalAPIs.addAll(extractMessageBrokerCalls(api.getControllerClassName(), api.getServiceMethod(), allClasses));
      api.setExternalAPIs(externalAPIs);
    }
  }

  private List<APIInfo.ExternalAPI> extractExternalAPICalls(ClassNode classNode, MethodNode methodNode,
      List<ClassNode> allClasses) {
    List<APIInfo.ExternalAPI> externalAPIs = new ArrayList<>();
    for (AbstractInsnNode insn : methodNode.instructions) {
      if (insn instanceof MethodInsnNode) {
        MethodInsnNode methodInsn = (MethodInsnNode) insn;
        APIInfo.ExternalAPI externalAPI = extractExternalAPICall(methodInsn, classNode, methodNode, allClasses);
        if (externalAPI != null) {
          externalAPIs.add(externalAPI);
        }
      }
    }
    return externalAPIs;
  }

  private APIInfo.ExternalAPI extractExternalAPICall(MethodInsnNode methodInsn, ClassNode classNode,
      MethodNode methodNode, List<ClassNode> allClasses) {
    String url = extractUrlFromMethodInsn(methodInsn, classNode, methodNode);
    String httpMethod = extractHttpMethodFromMethodInsn(methodInsn);
    boolean isAsync = isAsyncCall(methodInsn);
    String targetService = extractTargetService(methodInsn, allClasses);
    String responseType = extractResponseType(methodInsn, methodNode);

    if (url != null && httpMethod != null) {
      return new APIInfo.ExternalAPI(url, httpMethod, methodNode.name, isAsync, targetService, responseType);
    }
    return null;
  }

  private boolean isAsyncCall(MethodInsnNode methodInsn) {
    return methodInsn.name.contains("Async") || methodInsn.desc.contains("Mono") || methodInsn.desc.contains("Flux");
  }

  private String extractTargetService(MethodInsnNode methodInsn, List<ClassNode> allClasses) {
    ClassNode targetClass = allClasses.stream()
        .filter(cn -> cn.name.equals(methodInsn.owner))
        .findFirst()
        .orElse(null);

    if (targetClass != null && targetClass.visibleAnnotations != null) {
      for (AnnotationNode an : targetClass.visibleAnnotations) {
        if (an.desc.contains("FeignClient")) {
          return extractAnnotationValue(an, "name");
        }
      }
    }
    return "UnknownService";
  }

  private String extractResponseType(MethodInsnNode methodInsn, MethodNode methodNode) {
    if (methodNode.desc == null) {
      return "Unknown";
    }

    Type returnType = Type.getReturnType(methodNode.desc);
    if (returnType.getSort() == Type.OBJECT) {
      String className = returnType.getClassName();
      if (className.contains("Mono") || className.contains("Flux")) {
        if (methodNode.signature != null) {
          Type[] genericTypes = Type.getArgumentTypes(methodNode.signature);
          if (genericTypes.length > 0) {
            return genericTypes[0].getClassName();
          }
        }
      }
      return className;
    }
    return returnType.getClassName();
  }

  private String extractUrlFromMethodInsn(MethodInsnNode methodInsn, ClassNode classNode, MethodNode methodNode) {
    String url = null;

    // Check if the method is annotated with a URL
    if (methodNode.visibleAnnotations != null) {
      for (AnnotationNode an : methodNode.visibleAnnotations) {
        if (an.desc.contains("RequestMapping") || an.desc.contains("GetMapping") ||
            an.desc.contains("PostMapping") || an.desc.contains("PutMapping") ||
            an.desc.contains("DeleteMapping") || an.desc.contains("PatchMapping")) {
          url = extractAnnotationValue(an, "value");
          if (url != null) {
            break;
          }
        }
      }
    }

    // If URL is not found in method annotation, check class-level annotation
    if (url == null && classNode.visibleAnnotations != null) {
      for (AnnotationNode an : classNode.visibleAnnotations) {
        if (an.desc.contains("RequestMapping")) {
          String classLevelPath = extractAnnotationValue(an, "value");
          if (classLevelPath != null) {
            url = classLevelPath;
            break;
          }
        }
      }
    }

    // If still not found, try to extract from method instructions
    if (url == null) {
      for (AbstractInsnNode insn : methodNode.instructions) {
        if (insn instanceof LdcInsnNode) {
          LdcInsnNode ldcInsn = (LdcInsnNode) insn;
          if (ldcInsn.cst instanceof String) {
            String potentialUrl = (String) ldcInsn.cst;
            if (potentialUrl.startsWith("/") || potentialUrl.startsWith("http")) {
              url = potentialUrl;
              break;
            }
          }
        }
      }
    }

    return url;
  }

  private String extractHttpMethodFromMethodInsn(MethodInsnNode methodInsn) {
    if (methodInsn == null) {
      return null;
    }

    String httpMethod = extractHttpMethodFromName(methodInsn.name);
    if (httpMethod != null) {
      return httpMethod;
    }

    ClassNode ownerClass = findClassNode(methodInsn.owner);
    if (ownerClass == null) {
      return null;
    }

    MethodNode targetMethod = findMethodNode(ownerClass, methodInsn.name, methodInsn.desc);
    if (targetMethod == null) {
      return null;
    }

    httpMethod = extractHttpMethodFromAnnotations(targetMethod);
    if (httpMethod != null) {
      return httpMethod;
    }

    return extractHttpMethodFromInstructions(targetMethod);
  }

  private MethodNode findMethodNode(ClassNode classNode, String methodName, String methodDesc) {
    return classNode.methods.stream()
        .filter(mn -> mn.name.equals(methodName) && mn.desc.equals(methodDesc))
        .findFirst()
        .orElse(null);
  }

  private String extractHttpMethodFromAnnotations(MethodNode methodNode) {
    if (methodNode.visibleAnnotations == null) {
      return null;
    }

    for (AnnotationNode an : methodNode.visibleAnnotations) {
      if (an.desc.contains("GetMapping"))
        return "GET";
      if (an.desc.contains("PostMapping"))
        return "POST";
      if (an.desc.contains("PutMapping"))
        return "PUT";
      if (an.desc.contains("DeleteMapping"))
        return "DELETE";
      if (an.desc.contains("PatchMapping"))
        return "PATCH";
      if (an.desc.contains("RequestMapping")) {
        String method = extractAnnotationValue(an, "method");
        if (method != null) {
          return method.toUpperCase();
        }
      }
    }

    return null;
  }

  private String extractHttpMethodFromInstructions(MethodNode methodNode) {
    for (AbstractInsnNode insn : methodNode.instructions) {
      if (insn instanceof LdcInsnNode) {
        LdcInsnNode ldcInsn = (LdcInsnNode) insn;
        if (ldcInsn.cst instanceof String) {
          String constant = ((String) ldcInsn.cst).toUpperCase();
          if (constant.equals("GET") || constant.equals("POST") || constant.equals("PUT") ||
              constant.equals("DELETE") || constant.equals("PATCH")) {
            return constant;
          }
        }
      }
    }
    return null;
  }

  private String extractHttpMethodFromName(String methodName) {
    String lowerCaseName = methodName.toLowerCase();
    if (lowerCaseName.contains("get"))
      return "GET";
    if (lowerCaseName.contains("post"))
      return "POST";
    if (lowerCaseName.contains("put"))
      return "PUT";
    if (lowerCaseName.contains("delete"))
      return "DELETE";
    if (lowerCaseName.contains("patch"))
      return "PATCH";
    return null;
  }

  private ClassNode findClassNode(String className) {
    return allClasses.stream()
        .filter(cn -> cn.name.equals(className))
        .findFirst()
        .orElse(null);
  }
}