package com.example;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
  private final String fileName;

  public APIInventoryExtractor(Map<String, String> configProperties, String fileName) {
    this.configProperties = configProperties;
    String PORT = configProperties.getOrDefault("server.port", "8080");
    this.basePath = "http://" + configProperties.getOrDefault("spring.application.name", "") + ":" + PORT;
    this.enabledEndpoints = parseCommaSeparatedConfig("api.enabled-endpoints");
    this.ignoredPaths = parseCommaSeparatedConfig("api.ignored-paths");
    this.fileName = fileName;
    logger.info("APIInventoryExtractor initialized with base path: {}", basePath);
  }

  public List<APIInfo> extractExposedAPIs(List<ClassNode> allClasses) {
    logger.info("Extracting exposed APIs");
    return allClasses.stream()
        .filter(this::isApiClass)
        .flatMap(classNode -> extractAPIsFromClass(classNode).stream())
        .filter(this::isApiEnabled)
        .collect(Collectors.toList());
  }

  private boolean isApiClass(ClassNode classNode) {
    return isController(classNode) || isService(classNode);
  }

  private List<APIInfo> extractAPIsFromClass(ClassNode classNode) {
    return classNode.methods.stream()
        .map(methodNode -> extractAPIInfo(classNode, methodNode))
        .filter(Objects::nonNull)
        .collect(Collectors.toList());
  }

  private APIInfo extractAPIInfo(ClassNode classNode, MethodNode methodNode) {
    String httpMethod = extractHttpMethod(methodNode);
    String methodPath = extractPath(classNode, methodNode);

    if (httpMethod == null || methodPath == null) {
      return null;
    }

    String serviceName = extractServiceName(classNode);
    String apiName = extractApiName(methodNode);
    String description = extractDescription(methodNode);
    String apiEndpoint = constructApiEndpoint(methodPath);
    String methodName = methodNode.name;
    String className = classNode.name;
    String version = extractVersion(classNode, methodNode);
    List<String> serviceDependencies = extractServiceDependencies(methodNode);
    String returnType = extractReturnType(methodNode);
    List<APIInfo.ParameterInfo> parameters = extractParameters(methodNode);
    boolean isAsync = isAsyncMethod(methodNode);

    return new APIInfo(
        serviceName,
        apiName,
        methodName,
        description,
        apiEndpoint,
        methodPath,
        httpMethod,
        version,
        serviceDependencies,
        returnType,
        parameters,
        className,
        isAsync);
  }

  private String constructApiEndpoint(String methodPath) {

    // Ensure the path starts with a slash
    return basePath + "/" + trimSlashes(methodPath);
  }

  private String trimSlashes(String path) {
    if (path == null || path.isEmpty()) {
      return "";
    }
    return path.replaceAll("^/+|/+$", "");
  }

  private String extractServiceName(ClassNode classNode) {
    // Remove file extension if present
    int dotIndex = fileName.lastIndexOf('.');
    if (dotIndex > 0) {
      return fileName.substring(0, dotIndex);
    }
    return fileName;
  }

  private String extractApiName(MethodNode methodNode) {
    return methodNode.name;
  }

  private String extractDescription(MethodNode methodNode) {
    // Check for @ApiOperation annotation (Swagger)
    String swaggerDescription = extractSwaggerDescription(methodNode);
    if (swaggerDescription != null) {
      return swaggerDescription;
    }

    // Check for @Description annotation (custom)
    String customDescription = extractCustomDescription(methodNode);
    if (customDescription != null) {
      return customDescription;
    }

    // Extract from JavaDoc comment
    String javadocDescription = extractJavadocDescription(methodNode);
    if (javadocDescription != null) {
      return javadocDescription;
    }

    // Default to method name if no description found
    return methodNode.name;
  }

  private String extractSwaggerDescription(MethodNode methodNode) {
    if (methodNode.visibleAnnotations != null) {
      for (AnnotationNode annotation : methodNode.visibleAnnotations) {
        if (annotation.desc.contains("ApiOperation")) {
          for (int i = 0; i < annotation.values.size(); i += 2) {
            if (annotation.values.get(i).equals("value")) {
              return (String) annotation.values.get(i + 1);
            }
          }
        }
      }
    }
    return null;
  }

  private String extractCustomDescription(MethodNode methodNode) {
    if (methodNode.visibleAnnotations != null) {
      for (AnnotationNode annotation : methodNode.visibleAnnotations) {
        if (annotation.desc.contains("Description")) {
          for (int i = 0; i < annotation.values.size(); i += 2) {
            if (annotation.values.get(i).equals("value")) {
              return (String) annotation.values.get(i + 1);
            }
          }
        }
      }
    }
    return null;
  }

  private String extractJavadocDescription(MethodNode methodNode) {
    if (methodNode.visibleAnnotations != null) {
      for (AnnotationNode annotation : methodNode.visibleAnnotations) {
        if (annotation.desc.equals("Ljava/lang/Deprecated;")) {
          List<Object> values = annotation.values;
          if (values != null && values.size() > 1) {
            Object descriptionObj = values.get(1);
            if (descriptionObj instanceof String) {
              String fullJavadoc = (String) descriptionObj;
              // Extract the first sentence from the JavaDoc
              Pattern pattern = Pattern.compile("^\\s*([^.!?]+[.!?])");
              Matcher matcher = pattern.matcher(fullJavadoc);
              if (matcher.find()) {
                return matcher.group(1).trim();
              }
            }
          }
        }
      }
    }
    return null;
  }

  private String extractVersion(ClassNode classNode, MethodNode methodNode) {
    // Extract version from class or method annotations
    return "1.0";
  }

  private List<String> extractServiceDependencies(MethodNode methodNode) {
    Set<String> dependencies = new HashSet<>();

    if (methodNode.instructions == null) {
      return new ArrayList<>(dependencies);
    }

    for (AbstractInsnNode insn : methodNode.instructions) {
      if (insn instanceof MethodInsnNode) {
        MethodInsnNode methodInsn = (MethodInsnNode) insn;
        String dependency = extractDependency(methodInsn);
        if (dependency != null) {
          dependencies.add(dependency);
        }
      }
    }

    return new ArrayList<>(dependencies);
  }

  private String extractDependency(MethodInsnNode methodInsn) {
    String owner = methodInsn.owner.replace('/', '.');

    // Check if the owner class is a known service from config properties
    String serviceName = getServiceNameFromConfig(owner);
    if (serviceName != null) {
      return serviceName;
    }

    // Check for common patterns in class names that might indicate a service
    if (owner.endsWith("Service") || owner.endsWith("Client") || owner.endsWith("Repository")) {
      return owner.substring(owner.lastIndexOf('.') + 1);
    }

    // Check for specific method calls that might indicate a service dependency
    if (isHttpClientMethod(methodInsn) || isRepositoryMethod(methodInsn)) {
      return owner.substring(owner.lastIndexOf('.') + 1);
    }

    return null;
  }

  private String getServiceNameFromConfig(String className) {
    // Check if the class name matches any service name in the config
    for (Map.Entry<String, String> entry : configProperties.entrySet()) {
      if (entry.getKey().startsWith("service.") && entry.getKey().endsWith(".class")) {
        if (entry.getValue().equals(className)) {
          // Extract service name from the config key
          return entry.getKey().substring("service.".length(), entry.getKey().length() - ".class".length());
        }
      }
    }
    return null;
  }

  private boolean isHttpClientMethod(MethodInsnNode methodInsn) {
    // Check for common HTTP client method names
    String methodName = methodInsn.name.toLowerCase();
    return methodName.equals("get") || methodName.equals("post") || methodName.equals("put")
        || methodName.equals("delete") || methodName.equals("patch");
  }

  private boolean isRepositoryMethod(MethodInsnNode methodInsn) {
    // Check for common repository method names
    String methodName = methodInsn.name.toLowerCase();
    return methodName.startsWith("find") || methodName.startsWith("save")
        || methodName.startsWith("delete") || methodName.startsWith("update");
  }

  private boolean isApiEnabled(APIInfo apiInfo) {
    String path = apiInfo.getApiEndpoint();

    // Check if the API is in the ignored paths
    if (isPathIgnored(path)) {
      logger.debug("API {} is ignored by configuration", path);
      return false;
    }

    // Check if the API is enabled
    if (isPathEnabled(path)) {
      logger.debug("API {} is enabled", path);
      return true;
    }

    logger.debug("API {} is not enabled by configuration", path);
    return false;
  }

  private boolean isPathIgnored(String path) {
    return ignoredPaths.stream()
        .anyMatch(ignoredPath -> path.startsWith(ignoredPath));
  }

  private boolean isPathEnabled(String path) {
    // If no specific endpoints are enabled, all non-ignored endpoints are
    // considered enabled
    if (enabledEndpoints.isEmpty()) {
      return true;
    }

    return enabledEndpoints.stream()
        .anyMatch(enabledPath -> path.startsWith(enabledPath));
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

  private boolean isController(ClassNode classNode) {
    return hasAnnotation(classNode, "RestController") || hasAnnotation(classNode, "Controller")
        || hasAnnotation(classNode, "Path");
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
    if (methodNode.localVariables != null) {
      String[] names = new String[methodNode.localVariables.size() - 1]; // -1 to exclude 'this'
      for (LocalVariableNode lvn : methodNode.localVariables) {
        if (lvn.index > 0) { // index 0 is 'this' for instance methods
          names[lvn.index - 1] = lvn.name;
        }
      }
      return names;
    }
    return null;
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
        // Add more annotation checks as needed
      }
    }
    return null;
  }

  private boolean isAsyncMethod(MethodNode methodNode) {
    return hasAnnotation(methodNode, "Async") ||
        Type.getReturnType(methodNode.desc).getClassName().contains("Mono") ||
        Type.getReturnType(methodNode.desc).getClassName().contains("Flux");
  }

  private String extractHttpMethod(MethodNode methodNode) {
    if (methodNode.visibleAnnotations != null) {
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
      }
    }
    return null;
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

  private boolean hasAnnotation(MethodNode methodNode, String annotationName) {
    return methodNode.visibleAnnotations != null &&
        methodNode.visibleAnnotations.stream()
            .anyMatch(an -> an.desc.contains(annotationName));
  }

}