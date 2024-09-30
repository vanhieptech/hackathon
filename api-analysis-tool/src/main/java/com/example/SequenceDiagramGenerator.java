package com.example;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.stream.Collectors;
import java.net.URL;
import java.net.MalformedURLException;

public class SequenceDiagramGenerator {
  private static final Logger logger = LoggerFactory.getLogger(SequenceDiagramGenerator.class);
  private final Map<String, String> configProperties;
  private final Map<String, Set<String>> classImports;
  private final Map<String, ClassNode> implementationCache = new HashMap<>();
  private final Set<String> processedMethods = new HashSet<>();
  private final List<String> orderedParticipants = new ArrayList<>();
  private final Map<String, String> implToInterfaceMap = new HashMap<>();
  private final Map<String, String> classToHostMap = new HashMap<>();
  private final Map<String, Set<String>> methodToAnnotations = new HashMap<>();
  private final boolean useNamingConventions;
  private final List<ImplementationMatcher> customRules;
  private final Map<String, Set<String>> serviceDependencies = new HashMap<>();
  private final APIInventoryExtractor apiInventoryExtractor;

  public interface ImplementationMatcher {
    boolean matches(ClassNode classNode, String interfaceName);
  }

  public SequenceDiagramGenerator(Map<String, String> configProperties, Map<String, Set<String>> classImports) {
    logger.info("Initializing SequenceDiagramGenerator");
    this.configProperties = configProperties;
    this.classImports = classImports;
    this.useNamingConventions = Boolean.parseBoolean(configProperties.getOrDefault("use.naming.conventions", "true"));
    this.customRules = initializeCustomRules();
    this.apiInventoryExtractor = new APIInventoryExtractor(configProperties, "");
  }

  private List<ImplementationMatcher> initializeCustomRules() {
    // Initialize custom rules for implementation matching
    List<ImplementationMatcher> rules = new ArrayList<>();
    // Add custom rules here if needed
    return rules;
  }

  public String generateSequenceDiagram(List<ClassNode> allClasses) {
    logger.info("Starting sequence diagram generation");
    StringBuilder sb = new StringBuilder();
    initializeDiagram(sb);

    logger.info("Scanning service dependencies");
    scanServiceDependencies(allClasses);
    logger.info("Found dependencies for {} services", serviceDependencies.size());

    logger.info("Extracting exposed APIs");
    List<APIInfo> exposedApis = extractExposedAPIs(allClasses);
    logger.info("Found {} exposed APIs", exposedApis.size());

    logger.info("Finding external calls");
    List<ExternalCallInfo> externalCalls = findExternalCalls(allClasses);
    logger.info("Found {} external calls", externalCalls.size());

    logger.info("Mapping implementations to interfaces");
    mapImplementationsToInterfaces(allClasses);

    logger.info("Finding WebClient host names");
    findWebClientHostNames(allClasses);

    logger.info("Mapping method annotations");
    mapMethodAnnotations(allClasses);

    logger.info("Appending participants to the diagram");
    appendParticipants(sb, exposedApis, externalCalls);

    logger.info("Generating sequences for each API");
    for (APIInfo api : exposedApis) {
      logger.info("Generating sequence for API: {}", api.getApiName());
      generateSequenceForAPI(sb, api, allClasses, externalCalls);
    }

    sb.append("@enduml");
    logger.info("Sequence diagram generation completed");
    return sb.toString();
  }

  private List<APIInfo> extractExposedAPIs(List<ClassNode> allClasses) {
    APIInventoryExtractor extractor = new APIInventoryExtractor(configProperties, "");
    return extractor.extractExposedAPIs(allClasses);
  }

  private List<ExternalCallInfo> findExternalCalls(List<ClassNode> allClasses) {
    ExternalCallScanner scanner = new ExternalCallScanner(configProperties, classImports);
    return scanner.findExternalCalls(allClasses);
  }

  private void mapImplementationsToInterfaces(List<ClassNode> allClasses) {
    for (ClassNode classNode : allClasses) {
      if (!classNode.interfaces.isEmpty()) {
        for (String interfaceName : classNode.interfaces) {
          String simpleInterfaceName = getSimpleClassName(interfaceName);
          String simpleClassName = getSimpleClassName(classNode.name);
          // Check if the class is a controller
          boolean isController = classNode.visibleAnnotations != null &&
              classNode.visibleAnnotations.stream()
                  .anyMatch(an -> an.desc.contains("RestController"));

          // If it's a controller or the class name contains the interface name
          if (isController || simpleClassName.contains(simpleInterfaceName)) {
            implToInterfaceMap.put(simpleClassName, simpleInterfaceName);
            break; // We've found a match, no need to check other interfaces
          }
        }
      }
    }
  }

  private void findWebClientHostNames(List<ClassNode> allClasses) {
    for (ClassNode classNode : allClasses) {
      String baseUrl = extractBaseUrl(classNode);
      if (baseUrl != null) {
        classToHostMap.put(classNode.name, baseUrl);
      }
    }
  }

  private void handleServiceDiscovery(StringBuilder sb, ClassNode classNode) {
    if (hasAnnotation(classNode, "EnableDiscoveryClient") || hasAnnotation(classNode, "EnableEurekaClient")) {
      sb.append("note over ").append(getSimpleClassName(classNode.name))
          .append(" : Registered with Service Discovery\n");
    }
  }

  private String extractBaseUrl(ClassNode classNode) {
    // First, try to extract from @Value annotations
    String baseUrl = extractBaseUrlFromAnnotations(classNode);
    if (baseUrl != null) {
      return baseUrl;
    }

    // If not found, try to extract from constructor or methods
    baseUrl = extractBaseUrlFromMethods(classNode);
    if (baseUrl != null) {
      return baseUrl;
    }

    // If still not found, try to extract from static fields
    return extractBaseUrlFromFields(classNode);
  }

  private String extractBaseUrlFromAnnotations(ClassNode classNode) {
    for (FieldNode field : classNode.fields) {
      if (field.visibleAnnotations != null) {
        for (AnnotationNode annotation : field.visibleAnnotations) {
          if (annotation.desc.contains("Value")) {
            List<Object> values = annotation.values;
            if (values != null && values.size() >= 2 && values.get(1) instanceof String) {
              String propertyKey = (String) values.get(1);
              propertyKey = propertyKey.replaceAll("[\\$\\{\\}]", "");
              return resolvePropertyValue(propertyKey);
            }
          }
        }
      }
    }
    return null;
  }

  private String extractBaseUrlFromMethods(ClassNode classNode) {
    for (MethodNode method : classNode.methods) {
      if (method.name.equals("<init>") || method.name.equals("setBaseUrl")) {
        for (AbstractInsnNode insn : method.instructions) {
          if (insn instanceof LdcInsnNode) {
            LdcInsnNode ldcInsn = (LdcInsnNode) insn;
            if (ldcInsn.cst instanceof String) {
              String potentialUrl = (String) ldcInsn.cst;
              if (isValidUrl(potentialUrl)) {
                return potentialUrl;
              }
            }
          } else if (insn instanceof MethodInsnNode) {
            MethodInsnNode methodInsn = (MethodInsnNode) insn;
            if (methodInsn.name.equals("getProperty") || methodInsn.name.equals("getValue")) {
              // Try to resolve the property key
              String propertyKey = extractPropertyKey(methodInsn);
              if (propertyKey != null) {
                return resolvePropertyValue(propertyKey);
              }
            }
          }
        }
      }
    }
    return null;
  }

  private String extractBaseUrlFromFields(ClassNode classNode) {
    for (FieldNode field : classNode.fields) {
      if (field.value instanceof String) {
        String potentialUrl = (String) field.value;
        if (isValidUrl(potentialUrl)) {
          return potentialUrl;
        }
      }
    }
    return null;
  }

  private boolean isValidUrl(String url) {
    return url != null && (url.startsWith("http://") || url.startsWith("https://"));
  }

  private String extractPropertyKey(MethodInsnNode methodInsn) {
    AbstractInsnNode prevInsn = methodInsn.getPrevious();
    if (prevInsn instanceof LdcInsnNode) {
      LdcInsnNode ldcInsn = (LdcInsnNode) prevInsn;
      if (ldcInsn.cst instanceof String) {
        return (String) ldcInsn.cst;
      }
    }
    return null;
  }

  private String resolvePropertyValue(String key) {
    // First, try to resolve from configProperties
    String value = configProperties.get(key);
    if (value != null) {
      return value;
    }

    // If not found in configProperties, try to resolve from environment variables
    value = System.getenv(key);
    if (value != null) {
      return value;
    }

    // If still not found, return the key itself (it might be resolved later)
    return "${" + key + "}";
  }

  private void mapMethodAnnotations(List<ClassNode> allClasses) {
    for (ClassNode classNode : allClasses) {
      for (MethodNode methodNode : classNode.methods) {
        if (methodNode.visibleAnnotations != null) {
          Set<String> annotations = methodNode.visibleAnnotations.stream()
              .map(an -> an.desc)
              .collect(Collectors.toSet());
          methodToAnnotations.put(classNode.name + "." + methodNode.name + methodNode.desc, annotations);
        }
      }
    }
  }

  private void generateSequenceForAPI(StringBuilder sb, APIInfo api, List<ClassNode> allClasses,
      List<ExternalCallInfo> externalCalls) {
    String serviceName = api.getServiceName();
    String methodName = api.getMethodName();

    sb.append("Client -> \"").append(serviceName).append("\" : ").append(api.getHttpMethod()).append(" ")
        .append(api.getPath()).append("\n");
    sb.append("activate \"").append(serviceName).append("\"\n");

    ClassNode classNode = findClassByName(allClasses, api.getClassName());
    if (classNode != null) {
      MethodNode methodNode = findMethodByName(classNode, methodName);
      if (methodNode != null) {
        processHighLevelCalls(sb, classNode, methodNode, allClasses, externalCalls, serviceName, 0, api);
      }
    }

    sb.append("Client <-- \"").append(serviceName).append("\" : Response\n");
    sb.append("deactivate \"").append(serviceName).append("\"\n\n");
  }

  private void processHighLevelCalls(StringBuilder sb, ClassNode classNode, MethodNode methodNode,
      List<ClassNode> allClasses, List<ExternalCallInfo> externalCalls, String currentService, int depth,
      APIInfo apiInfo) {
    if (depth > 10)
      return; // Increase the depth limit

    for (AbstractInsnNode insn : methodNode.instructions) {
      if (insn instanceof MethodInsnNode) {
        MethodInsnNode methodInsn = (MethodInsnNode) insn;
        String calledClass = getSimpleClassName(methodInsn.owner);
        String calledMethod = methodInsn.name;

        ExternalCallInfo externalCall = findExternalCall(methodInsn, externalCalls);
        if (externalCall != null) {
          String targetService = externalCall.getServiceName();
          sb.append("\"").append(currentService).append("\" -> \"").append(targetService).append("\"")
              .append(" : ").append(externalCall.getHttpMethod()).append(" ").append(externalCall.getUrl())
              .append("\n");
          sb.append("activate \"").append(targetService).append("\"\n");
          sb.append("\"").append(currentService).append("\" <-- \"").append(targetService).append("\" : Response\n");
          sb.append("deactivate \"").append(targetService).append("\"\n");

          // Add the external call to the APIInfo
          if (apiInfo != null && apiInfo.getExternalCalls() == null) {
            apiInfo.setExternalCalls(new ArrayList<>());
          }
          if (apiInfo != null) {
            apiInfo.getExternalCalls().add(externalCall);
          }
        } else if (isServiceMethod(methodInsn, allClasses)) {
          ClassNode targetClassNode = findClassByName(allClasses, methodInsn.owner);
          if (targetClassNode != null) {
            MethodNode targetMethodNode = findMethodByName(targetClassNode, calledMethod);
            if (targetMethodNode != null) {
              processHighLevelCalls(sb, targetClassNode, targetMethodNode, allClasses, externalCalls, currentService,
                  depth + 1, apiInfo);
            }
          }
        } else if (isWebClientOrRestTemplateCall(methodInsn)) {
          ExternalCallInfo newExternalCall = extractExternalCallInfo(methodInsn, classNode, methodNode);
          if (newExternalCall != null) {
            externalCalls.add(newExternalCall);
            if (apiInfo != null && apiInfo.getExternalCalls() == null) {
              apiInfo.setExternalCalls(new ArrayList<>());
            }
            if (apiInfo != null) {
              apiInfo.getExternalCalls().add(newExternalCall);
            }
          }
        }
      }
    }
  }

  private String getServiceNameFromClass(String className) {
    // This method should return the service name based on the class name
    // You might need to implement a mapping or naming convention
    return className.replaceAll("(Controller|Service|Repository)$", "");
  }

  private ExternalCallInfo findExternalCall(MethodInsnNode methodInsn, List<ExternalCallInfo> externalCalls) {
    return externalCalls.stream()
        .filter(
            ec -> ec.getCallerClassName().equals(methodInsn.owner) && ec.getCallerMethodName().equals(methodInsn.name))
        .findFirst()
        .orElse(null);
  }

  private boolean isServiceMethod(MethodInsnNode methodInsn, List<ClassNode> allClasses) {
    ClassNode calledClass = findClassByName(allClasses, methodInsn.owner);
    return calledClass != null && (isService(calledClass) || isRepository(calledClass));
  }

  private void handleAspects(StringBuilder sb, ClassNode classNode, MethodNode methodNode, String callerName) {
    Set<String> annotations = methodToAnnotations.get(classNode.name + "." + methodNode.name + methodNode.desc);
    if (annotations != null) {
      if (annotations.stream().anyMatch(an -> an.contains("Transactional"))) {
        sb.append("\"").append(callerName).append("\" -> \"Transaction Manager\" : Begin Transaction\n");
      }
      if (annotations.stream().anyMatch(an -> an.contains("Cacheable"))) {
        sb.append("\"").append(callerName).append("\" -> \"Cache Manager\" : Check Cache\n");
      }
    }
  }

  private void handleQuarkusAnnotations(StringBuilder sb, ClassNode classNode, MethodNode methodNode) {
    if (hasMethodAnnotation(methodNode, "Transactional")) {
      sb.append("note over ").append(getSimpleClassName(classNode.name)).append(" : @Transactional\n");
    }
    if (hasMethodAnnotation(methodNode, "Inject")) {
      sb.append("note over ").append(getSimpleClassName(classNode.name)).append(" : @Inject\n");
    }
  }

  private boolean hasMethodAnnotation(MethodNode methodNode, String annotationName) {
    return methodNode.visibleAnnotations != null &&
        methodNode.visibleAnnotations.stream().anyMatch(a -> a.desc.contains(annotationName));
  }

  private String extractReturnType(MethodNode methodNode) {
    return apiInventoryExtractor.extractReturnType(methodNode);
  }

  private List<APIInfo.ParameterInfo> extractParameters(MethodNode methodNode) {
    return apiInventoryExtractor.extractParameters(methodNode);
  }

  private String getSimpleClassName(String fullClassName) {
    logger.trace("Getting simple class name for: {}", fullClassName);
    if (fullClassName == null || fullClassName.isEmpty()) {
      return "";
    }

    // Remove array notation if present
    int arrayIndex = fullClassName.indexOf('[');
    if (arrayIndex != -1) {
      fullClassName = fullClassName.substring(0, arrayIndex);
    }

    // Handle inner classes
    int innerClassIndex = fullClassName.lastIndexOf('$');
    if (innerClassIndex != -1) {
      fullClassName = fullClassName.substring(innerClassIndex + 1);
    }

    // Extract the class name after the last dot or slash
    int lastSeparatorIndex = Math.max(fullClassName.lastIndexOf('.'), fullClassName.lastIndexOf('/'));
    String simpleName = lastSeparatorIndex != -1 ? fullClassName.substring(lastSeparatorIndex + 1) : fullClassName;

    // Check if this class is an implementation and return the interface name if it
    // exists
    String result = implToInterfaceMap.getOrDefault(simpleName, simpleName);
    logger.trace("Simple class name result: {}", result);
    return result;
  }

  private void appendParticipants(StringBuilder sb, List<APIInfo> apis, List<ExternalCallInfo> externalCalls) {
    Set<String> addedParticipants = new HashSet<>();
    List<String> orderedParticipants = new ArrayList<>();

    // Add Client
    orderedParticipants.add("Client");
    addedParticipants.add("Client");

    // Add the main application service
    String mainServiceName = getMainServiceName();
    if (mainServiceName != null) {
      orderedParticipants.add(mainServiceName);
      addedParticipants.add(mainServiceName);
    }

    // Add services from APIInfo
    for (APIInfo api : apis) {
      String serviceName = api.getServiceName();
      if (serviceName != null && !addedParticipants.contains(serviceName)) {
        orderedParticipants.add(serviceName);
        addedParticipants.add(serviceName);
      }
    }

    // Add services from ExternalCallInfo
    for (ExternalCallInfo externalCall : externalCalls) {
      String serviceName = externalCall.getServiceName();
      if (serviceName != null && !addedParticipants.contains(serviceName)) {
        orderedParticipants.add(serviceName);
        addedParticipants.add(serviceName);
      }
    }

    // Append participants to the diagram
    for (String participant : orderedParticipants) {
      sb.append("participant \"").append(participant).append("\"\n");
    }
    sb.append("\n");
  }

  private String getMainServiceName() {
    // Try to get the service name from spring.application.name
    String serviceName = configProperties.get("spring.application.name");

    // If not found, try to extract from other common properties
    if (serviceName == null || serviceName.isEmpty()) {
      serviceName = configProperties.get("application.name");
    }

    // If still not found, use a default name
    if (serviceName == null || serviceName.isEmpty()) {
      serviceName = "MainService";
    }

    return serviceName;
  }

  private String getParticipantName(ClassNode classNode) {
    String simpleName = getSimpleClassName(classNode.name);
    if (classNode.interfaces != null && !classNode.interfaces.isEmpty()) {
      for (String interfaceName : classNode.interfaces) {
        if (interfaceName.endsWith("Api")) {
          return getSimpleClassName(interfaceName);
        }
      }
    }
    return simpleName;
  }

  private void scanServiceDependencies(List<ClassNode> allClasses) {
    for (ClassNode classNode : allClasses) {
      if (isService(classNode) || isController(classNode)) {
        String serviceName = getSimpleClassName(classNode.name);
        Set<String> dependencies = new HashSet<>();

        // Check constructor dependencies
        for (MethodNode methodNode : classNode.methods) {
          if (methodNode.name.equals("<init>")) {
            Type[] argumentTypes = Type.getArgumentTypes(methodNode.desc);
            for (Type argType : argumentTypes) {
              String dependencyName = getSimpleClassName(argType.getClassName());
              if (isService(findClassByName(allClasses, argType.getClassName())) ||
                  isRepository(findClassByName(allClasses, argType.getClassName()))) {
                dependencies.add(dependencyName);
              }
            }
          }
        }

        // Check field dependencies
        for (FieldNode fieldNode : classNode.fields) {
          String dependencyName = getSimpleClassName(Type.getType(fieldNode.desc).getClassName());
          if (isService(findClassByName(allClasses, Type.getType(fieldNode.desc).getClassName())) ||
              isRepository(findClassByName(allClasses, Type.getType(fieldNode.desc).getClassName()))) {
            dependencies.add(dependencyName);
          }
        }

        if (!dependencies.isEmpty()) {
          serviceDependencies.put(serviceName, dependencies);
        }
      }
    }
  }

  private boolean isController(ClassNode classNode) {
    return hasAnnotation(classNode, "RestController") || hasAnnotation(classNode, "Controller");
  }

  private boolean isService(ClassNode classNode) {
    return classNode != null && (hasAnnotation(classNode, "Service")
        || classNode.name.endsWith("Service")
        || classNode.name.endsWith("ServiceImpl"));
  }

  private boolean isRepository(ClassNode classNode) {
    return classNode != null && (hasAnnotation(classNode, "Repository")
        || classNode.name.endsWith("Repository"));
  }

  private boolean hasAnnotation(ClassNode classNode, String annotationName) {
    return classNode.visibleAnnotations != null &&
        classNode.visibleAnnotations.stream().anyMatch(a -> a.desc.contains(annotationName));
  }

  private void initializeDiagram(StringBuilder sb) {
    sb.append("@startuml\n");
    sb.append("!pragma teoz true\n");
    sb.append("skinparam sequenceArrowThickness 2\n");
    sb.append("skinparam roundcorner 20\n");
    sb.append("skinparam maxmessagesize 60\n");
    sb.append("skinparam responseMessageBelowArrow true\n");
    sb.append("skinparam ParticipantPadding 20\n");
    sb.append("skinparam BoxPadding 10\n");
    sb.append("skinparam SequenceGroupBodyBackgroundColor transparent\n");
    sb.append("skinparam SequenceGroupBorderColor gray\n");
    sb.append("skinparam SequenceGroupFontStyle italic\n\n");
  }

  private ClassNode findClassByName(List<ClassNode> allClasses, String className) {
    return allClasses.stream()
        .filter(cn -> cn.name.equals(className))
        .findFirst()
        .orElse(null);
  }

  private MethodNode findMethodByName(ClassNode classNode, String methodName) {
    return classNode.methods.stream()
        .filter(mn -> mn.name.equals(methodName))
        .findFirst()
        .orElse(null);
  }

  private boolean isWebClientOrRestTemplateCall(MethodInsnNode methodInsn) {
    return methodInsn.owner.contains("WebClient") || methodInsn.owner.contains("RestTemplate");
  }

  private ExternalCallInfo extractExternalCallInfo(MethodInsnNode methodInsn, ClassNode classNode, MethodNode methodNode) {
    String url = extractUrlFromMethodInsn(methodInsn, classNode, methodNode);
    String httpMethod = extractHttpMethodFromMethodInsn(methodInsn);
    String serviceName = extractServiceNameFromUrl(url);
    String responseType = extractResponseType(methodNode, methodInsn);
    List<String> parameters = extractParameters(methodNode, methodInsn);
    
    if (url != null && httpMethod != null) {
        return new ExternalCallInfo(
            serviceName,
            url,
            httpMethod,
            methodInsn.name,
            "External call from " + classNode.name + "." + methodNode.name,
            responseType,
            parameters.toArray(new String[0])
        );
    }
    return null;
  }

  private String extractUrlFromMethodInsn(MethodInsnNode methodInsn, ClassNode classNode, MethodNode methodNode) {
    String baseUrl = classToHostMap.get(classNode.name);
    String endpoint = null;
    
    for (AbstractInsnNode insn = methodInsn.getPrevious(); insn != null; insn = insn.getPrevious()) {
        if (insn instanceof LdcInsnNode && ((LdcInsnNode) insn).cst instanceof String) {
            String constant = (String) ((LdcInsnNode) insn).cst;
            if (constant.startsWith("/") || constant.startsWith("http")) {
                endpoint = constant;
                break;
            }
        }
    }
    
    if (baseUrl != null && endpoint != null) {
        return combineUrls(baseUrl, endpoint);
    } else if (endpoint != null) {
        return endpoint;
    }
    
    return null;
  }

  private String extractHttpMethodFromMethodInsn(MethodInsnNode methodInsn) {
    String methodName = methodInsn.name.toLowerCase();
    if (methodName.contains("get")) return "GET";
    if (methodName.contains("post")) return "POST";
    if (methodName.contains("put")) return "PUT";
    if (methodName.contains("delete")) return "DELETE";
    if (methodName.contains("patch")) return "PATCH";
    return "UNKNOWN";
  }

  private String extractServiceNameFromUrl(String url) {
    if (url == null) return null;
    try {
        URL parsedUrl = new URL(url);
        String host = parsedUrl.getHost();
        String[] parts = host.split("\\.");
        if (parts.length > 0) {
            return parts[0];
        }
    } catch (MalformedURLException e) {
        logger.warn("Failed to parse URL: {}", url, e);
    }
    return "UnknownService";
  }

  private String extractResponseType(MethodNode methodNode, MethodInsnNode methodInsn) {
    for (AbstractInsnNode insn = methodInsn.getNext(); insn != null; insn = insn.getNext()) {
        if (insn instanceof MethodInsnNode) {
            MethodInsnNode mi = (MethodInsnNode) insn;
            if (mi.name.equals("bodyToMono") || mi.name.equals("bodyToFlux")) {
                AbstractInsnNode prevInsn = mi.getPrevious();
                if (prevInsn instanceof LdcInsnNode && ((LdcInsnNode) prevInsn).cst instanceof Type) {
                    return ((Type) ((LdcInsnNode) prevInsn).cst).getClassName();
                }
            }
        }
    }
    return Type.getReturnType(methodNode.desc).getClassName();
  }

  private List<String> extractParameters(MethodNode methodNode, MethodInsnNode methodInsn) {
    List<String> parameters = new ArrayList<>();
    for (AbstractInsnNode insn = methodInsn.getPrevious(); insn != null; insn = insn.getPrevious()) {
        if (insn instanceof VarInsnNode) {
            VarInsnNode varInsn = (VarInsnNode) insn;
            LocalVariableNode localVar = findLocalVariable(methodNode, varInsn.var, insn);
            if (localVar != null) {
                parameters.add(0, localVar.name + ": " + Type.getType(localVar.desc).getClassName());
            }
        }
        if (insn instanceof MethodInsnNode && ((MethodInsnNode) insn).name.equals("build")) {
            break;
        }
    }
    return parameters;
  }

  private LocalVariableNode findLocalVariable(MethodNode methodNode, int index, AbstractInsnNode currentInsn) {
    if (methodNode.localVariables == null) return null;
    for (LocalVariableNode localVar : methodNode.localVariables) {
        if (localVar.index == index) {
            int startIndex = methodNode.instructions.indexOf(localVar.start);
            int endIndex = methodNode.instructions.indexOf(localVar.end);
            int currentIndex = methodNode.instructions.indexOf(currentInsn);
            if (currentIndex >= startIndex && currentIndex < endIndex) {
                return localVar;
            }
        }
    }
    return null;
  }

  private String combineUrls(String baseUrl, String endpoint) {
    if (baseUrl == null)
      return endpoint;
    baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    endpoint = endpoint.startsWith("/") ? endpoint : "/" + endpoint;
    return baseUrl + endpoint;
  }
}