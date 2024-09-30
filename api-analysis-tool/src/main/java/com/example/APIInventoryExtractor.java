package com.example;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    List<APIInfo> apis = allClasses.stream()
        .filter(this::isApiClass)
        .flatMap(classNode -> extractAPIsFromClass(classNode).stream())
        .filter(this::isApiEnabled)
        .collect(Collectors.toList());
    logger.info("Extracted {} APIs", apis.size());
    return apis;
  }

  private boolean isApiClass(ClassNode classNode) {
    // Check for existing conditions (Controller or Service annotations)
    if (isController(classNode) || isService(classNode)) {
      return true;
    }

    // Check for OpenAPI generated classes
    if (classNode.visibleAnnotations != null) {
      for (AnnotationNode annotation : classNode.visibleAnnotations) {
        if (annotation.desc.contains("Generated") || annotation.desc.contains("OpenAPIDefinition")) {
          if (annotation.values != null) {
            for (int i = 0; i < annotation.values.size(); i += 2) {
              if (annotation.values.get(i).equals("value") &&
                  (annotation.values.get(i + 1).toString().contains("openapi-generator") ||
                      annotation.values.get(i + 1).toString().contains("swagger"))) {
                return true;
              }
            }
          }
        }
      }
    }

    // Check for OpenAPI interface or class naming conventions
    if ((classNode.access & Opcodes.ACC_INTERFACE) != 0 || (classNode.access & Opcodes.ACC_ABSTRACT) != 0) {
      if (classNode.name.endsWith("Api") || classNode.name.endsWith("Controller")
          || classNode.name.endsWith("Resource")) {
        return true;
      }
    }

    // Check for specific OpenAPI annotations
    if (hasOpenAPIAnnotations(classNode)) {
      return true;
    }

    // Check for Spring Web annotations
    if (hasSpringWebAnnotations(classNode)) {
      return true;
    }

    return false;
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
    // First, try to get the service name from spring.application.name
    String serviceName = configProperties.get("spring.application.name");

    // If not found, use the fileName without version
    if (serviceName == null || serviceName.isEmpty()) {
      serviceName = removeVersion(fileName);
    }

    // If still not found, fallback to the original method
    if (serviceName == null || serviceName.isEmpty()) {
      serviceName = classNode.name.substring(classNode.name.lastIndexOf('/') + 1);
      serviceName = serviceName.replaceAll("(Controller|Service|Resource|Api)$", "");
    }

    return serviceName;
  }

  private String removeVersion(String fileName) {
    if (fileName == null || fileName.isEmpty()) {
      return null;
    }
    // Remove file extension if present
    fileName = fileName.replaceFirst("[.][^.]+$", "");
    // Remove version number (assuming version is at the end and separated by a
    // hyphen)
    return fileName.replaceFirst("-\\d+(\\.\\d+)*$", "");
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
    String path = apiInfo.getPath();
    if (apiInfo.getHttpMethod().equals("UNKNOWN"))
      return false;

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
    if (path.isEmpty()) {
      return false;
    }
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

  private boolean hasAnnotation(MethodNode methodNode, String annotationName) {
    return methodNode.visibleAnnotations != null &&
        methodNode.visibleAnnotations.stream()
            .anyMatch(an -> an.desc.contains(annotationName));
  }

}