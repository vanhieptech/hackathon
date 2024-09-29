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

  public interface ImplementationMatcher {
    boolean matches(ClassNode classNode, String interfaceName);
  }

  public SequenceDiagramGenerator(Map<String, String> configProperties, Map<String, Set<String>> classImports) {
    this.configProperties = configProperties;
    this.classImports = classImports;
    this.useNamingConventions = Boolean.parseBoolean(configProperties.getOrDefault("use.naming.conventions", "true"));
    this.customRules = initializeCustomRules();
  }

  private List<ImplementationMatcher> initializeCustomRules() {
    // Initialize custom rules for implementation matching
    List<ImplementationMatcher> rules = new ArrayList<>();
    // Add custom rules here if needed
    return rules;
  }

  public String generateSequenceDiagram(List<ClassNode> allClasses) {
    StringBuilder sb = new StringBuilder();
    initializeDiagram(sb);

    List<APIInfo> exposedApis = extractExposedAPIs(allClasses);
    List<ExternalCallInfo> externalCalls = findExternalCalls(allClasses);

    mapImplementationsToInterfaces(allClasses);
    findWebClientHostNames(allClasses);
    mapMethodAnnotations(allClasses);

    appendParticipants(sb, allClasses, externalCalls);

    for (APIInfo api : exposedApis) {
      generateSequenceForAPI(sb, api, allClasses, externalCalls);
    }

    sb.append("@enduml");
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
      if (classNode.interfaces != null && !classNode.interfaces.isEmpty()) {
        for (String interfaceName : classNode.interfaces) {
          implToInterfaceMap.put(classNode.name, interfaceName);
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
    sb.append("== ").append(api.getApiName()).append(" ==\n");
    String controllerName = getSimpleClassName(api.getClassName());
    sb.append("\"Client\" -> \"").append(controllerName).append("\" : ")
        .append(api.getHttpMethod()).append(" ").append(api.getPath()).append("\n");
    sb.append("activate \"").append(controllerName).append("\"\n");

    ClassNode controllerClass = findClassByName(allClasses, api.getClassName());
    if (controllerClass != null) {
      handleServiceDiscovery(sb, controllerClass);
      MethodNode method = findMethodByName(controllerClass, api.getMethodName());
      if (method != null) {
        processedMethods.clear();
        processMethodFlow(sb, controllerClass, method, allClasses, 1, externalCalls);
      }
    }

    sb.append("deactivate \"").append(controllerName).append("\"\n\n");
  }

  private void processMethodFlow(StringBuilder sb, ClassNode classNode, MethodNode methodNode,
      List<ClassNode> allClasses, int depth, List<ExternalCallInfo> externalCalls) {
    if (depth > 10 || processedMethods.contains(classNode.name + "." + methodNode.name + methodNode.desc)) {
      return;
    }
    processedMethods.add(classNode.name + "." + methodNode.name + methodNode.desc);

    String callerName = getSimpleClassName(classNode.name);
    handleAspects(sb, classNode, methodNode, callerName);
    handleQuarkusAnnotations(sb, classNode, methodNode);

    for (AbstractInsnNode insn : methodNode.instructions) {
      if (insn instanceof MethodInsnNode) {
        MethodInsnNode methodInsn = (MethodInsnNode) insn;
        String calleeName = getSimpleClassName(methodInsn.owner);

        if (isServiceMethod(methodInsn)) {
          processServiceCall(sb, methodInsn, allClasses, depth, callerName, externalCalls);
        } else if (isRepositoryMethod(methodInsn)) {
          processRepositoryCall(sb, methodInsn, callerName);
        } else if (isExternalCall(methodInsn)) {
          processExternalCall(sb, methodInsn, externalCalls, callerName);
        }
      }
    }
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

  private void processServiceCall(StringBuilder sb, MethodInsnNode methodInsn, List<ClassNode> allClasses, int depth,
      String callerName, List<ExternalCallInfo> externalCalls) {
    String interfaceName = implToInterfaceMap.getOrDefault(methodInsn.owner, methodInsn.owner);
    String calleeName = getSimpleClassName(interfaceName);

    sb.append("\"").append(callerName).append("\" -> \"").append(calleeName).append("\" : ")
        .append(methodInsn.name).append("\n");
    sb.append("activate \"").append(calleeName).append("\"\n");

    ClassNode calleeClass = findImplementationClass(allClasses, methodInsn.owner);
    if (calleeClass != null) {
      MethodNode calleeMethod = findMethodByName(calleeClass, methodInsn.name);
      if (calleeMethod != null) {
        processMethodFlow(sb, calleeClass, calleeMethod, allClasses, depth + 1, externalCalls);
      }
    }

    sb.append("\"").append(calleeName).append("\" --> \"").append(callerName).append("\" : return\n");
    sb.append("deactivate \"").append(calleeName).append("\"\n");
  }

  private void processRepositoryCall(StringBuilder sb, MethodInsnNode methodInsn, String callerName) {
    String calleeName = getSimpleClassName(methodInsn.owner);
    sb.append("\"").append(callerName).append("\" -> \"").append(calleeName).append("\" : ")
        .append(methodInsn.name).append("\n");
    sb.append("activate \"").append(calleeName).append("\"\n");
    sb.append("\"").append(calleeName).append("\" -> \"Database\" : Execute Query\n");
    sb.append("\"Database\" --> \"").append(calleeName).append("\" : Query Result\n");
    sb.append("\"").append(calleeName).append("\" --> \"").append(callerName).append("\" : return\n");
    sb.append("deactivate \"").append(calleeName).append("\"\n");
  }

  private void processExternalCall(StringBuilder sb, MethodInsnNode methodInsn, List<ExternalCallInfo> externalCalls,
      String callerName) {
    ExternalCallInfo externalCall = findMatchingExternalCall(externalCalls, callerName, methodInsn.name);
    if (externalCall != null) {
      String externalServiceName = getSimpleClassName(externalCall.getServiceName());
      sb.append("\"").append(callerName).append("\" -> \"").append(externalServiceName).append("\" : ")
          .append(externalCall.getHttpMethod()).append(" ").append(externalCall.getUrl()).append("\n");
      sb.append("\"").append(externalServiceName).append("\" --> \"").append(callerName).append("\" : response\n");
    }
  }

  private boolean isServiceMethod(MethodInsnNode methodInsn) {
    return methodInsn.owner.endsWith("Service") || methodInsn.owner.endsWith("ServiceImpl");
  }

  private boolean isRepositoryMethod(MethodInsnNode methodInsn) {
    return methodInsn.owner.endsWith("Repository") || methodInsn.name.startsWith("find")
        || methodInsn.name.startsWith("save") || methodInsn.name.startsWith("delete");
  }

  private boolean isExternalCall(MethodInsnNode methodInsn) {
    return methodInsn.owner.contains("WebClient") || methodInsn.owner.contains("RestTemplate");
  }

  private ExternalCallInfo findMatchingExternalCall(List<ExternalCallInfo> externalCalls, String callerMethod,
      String calleeMethod) {
    return externalCalls.stream()
        .filter(call -> call.getCallerMethod().equals(callerMethod) &&
            call.getHttpMethod().equalsIgnoreCase(getHttpMethodFromCallee(calleeMethod)))
        .findFirst()
        .orElse(null);
  }

  private String getHttpMethodFromCallee(String calleeMethod) {
    if (calleeMethod.contains("get"))
      return "GET";
    if (calleeMethod.contains("post"))
      return "POST";
    if (calleeMethod.contains("put"))
      return "PUT";
    if (calleeMethod.contains("delete"))
      return "DELETE";
    return "UNKNOWN";
  }

  private ClassNode findImplementationClass(List<ClassNode> allClasses, String interfaceName) {
    if (implementationCache.containsKey(interfaceName)) {
      return implementationCache.get(interfaceName);
    }

    ClassNode result = findImplementationClassInternal(allClasses, interfaceName);
    implementationCache.put(interfaceName, result);
    return result;
  }

  private ClassNode findImplementationClassInternal(List<ClassNode> allClasses, String interfaceName) {
    // Step 1: Check if it's a concrete class
    ClassNode concreteClass = findClassByName(allClasses, interfaceName);
    if (concreteClass != null && !isInterface(concreteClass)) {
      logger.info("Found concrete class: {}", concreteClass.name);
      return concreteClass;
    }

    // Step 2: Look for direct implementations
    List<ClassNode> directImplementations = findDirectImplementations(allClasses, interfaceName);
    if (!directImplementations.isEmpty()) {
      return handleMultipleImplementations(directImplementations, interfaceName, "direct");
    }

    // Step 3: Look for indirect implementations (through interface inheritance)
    ClassNode indirectImplementation = findIndirectImplementation(allClasses, interfaceName);
    if (indirectImplementation != null) {
      return indirectImplementation;
    }

    // Step 4: Handle Spring Data repositories and other dynamic proxies
    if (isSpringDataRepository(interfaceName)) {
      logger.info("Assuming Spring Data repository or DAO for: {}", interfaceName);
      return concreteClass; // Return the interface itself
    }

    // Step 5: Look for abstract class implementations
    List<ClassNode> abstractImplementations = findAbstractImplementations(allClasses, interfaceName);
    if (!abstractImplementations.isEmpty()) {
      return handleMultipleImplementations(abstractImplementations, interfaceName, "abstract");
    }

    // Step 6: Apply custom rules
    ClassNode customRuleMatch = applyCustomRules(allClasses, interfaceName);
    if (customRuleMatch != null) {
      return customRuleMatch;
    }

    // Step 7: Look for classes with similar names (if naming conventions are
    // enabled)
    if (useNamingConventions) {
      ClassNode namingConventionMatch = findByNamingConvention(allClasses, interfaceName);
      if (namingConventionMatch != null) {
        return namingConventionMatch;
      }
    }

    logger.warn("No implementation found for {}. Using the interface/class itself.", interfaceName);
    return concreteClass; // Return the interface/class itself if no implementation is found
  }

  private List<ClassNode> findDirectImplementations(List<ClassNode> allClasses, String interfaceName) {
    return allClasses.stream()
        .filter(classNode -> classNode.interfaces.contains(interfaceName))
        .collect(Collectors.toList());
  }

  private ClassNode handleMultipleImplementations(List<ClassNode> implementations, String interfaceName,
      String implementationType) {
    if (implementations.size() > 1) {
      logger.warn("Multiple {} implementations found for {}: {}", implementationType, interfaceName,
          implementations.stream().map(cn -> cn.name).collect(Collectors.joining(", ")));
    }
    logger.info("Found {} implementation for {}: {}", implementationType, interfaceName, implementations.get(0).name);
    return implementations.get(0);
  }

  private ClassNode findIndirectImplementation(List<ClassNode> allClasses, String interfaceName) {
    for (ClassNode classNode : allClasses) {
      if (isIndirectImplementation(classNode, interfaceName, allClasses, new HashSet<>())) {
        logger.info("Found indirect implementation for {}: {}", interfaceName, classNode.name);
        return classNode;
      }
    }
    return null;
  }

  private boolean isIndirectImplementation(ClassNode classNode, String targetInterface, List<ClassNode> allClasses,
      Set<String> visited) {
    if (visited.contains(classNode.name)) {
      return false;
    }
    visited.add(classNode.name);

    for (String directInterface : classNode.interfaces) {
      if (directInterface.equals(targetInterface)) {
        return true;
      }
      ClassNode interfaceNode = findClassByName(allClasses, directInterface);
      if (interfaceNode != null && isIndirectImplementation(interfaceNode, targetInterface, allClasses, visited)) {
        return true;
      }
    }
    return false;
  }

  private boolean isSpringDataRepository(String interfaceName) {
    return interfaceName.contains("Repository") || interfaceName.endsWith("Dao");
  }

  private List<ClassNode> findAbstractImplementations(List<ClassNode> allClasses, String interfaceName) {
    return allClasses.stream()
        .filter(classNode -> isSubclassOf(classNode, interfaceName, allClasses))
        .collect(Collectors.toList());
  }

  private boolean isSubclassOf(ClassNode classNode, String superClassName, List<ClassNode> allClasses) {
    if (classNode.superName.equals(superClassName)) {
      return true;
    }
    ClassNode superClass = findClassByName(allClasses, classNode.superName);
    return superClass != null && isSubclassOf(superClass, superClassName, allClasses);
  }

  private ClassNode applyCustomRules(List<ClassNode> allClasses, String interfaceName) {
    for (ImplementationMatcher matcher : customRules) {
      Optional<ClassNode> match = allClasses.stream()
          .filter(classNode -> matcher.matches(classNode, interfaceName))
          .findFirst();
      if (match.isPresent()) {
        logger.info("Found implementation for {} using custom rule: {}", interfaceName, match.get().name);
        return match.get();
      }
    }
    return null;
  }

  private ClassNode findByNamingConvention(List<ClassNode> allClasses, String interfaceName) {
    String simpleInterfaceName = getSimpleClassName(interfaceName);
    List<ClassNode> potentialMatches = allClasses.stream()
        .filter(classNode -> getSimpleClassName(classNode.name).contains(simpleInterfaceName))
        .collect(Collectors.toList());

    if (!potentialMatches.isEmpty()) {
      logger.warn("No exact implementation found for {}. Using potential match based on naming convention: {}",
          interfaceName, potentialMatches.get(0).name);
      return potentialMatches.get(0);
    }
    return null;
  }

  private boolean isInterface(ClassNode classNode) {
    return (classNode.access & Opcodes.ACC_INTERFACE) != 0;
  }

  private String getSimpleClassName(String fullClassName) {
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
    return lastSeparatorIndex != -1 ? fullClassName.substring(lastSeparatorIndex + 1) : fullClassName;
  }

  private void appendParticipants(StringBuilder sb, List<ClassNode> allClasses, List<ExternalCallInfo> externalCalls) {
    Set<String> participants = new LinkedHashSet<>();
    participants.add("Client");

    for (ClassNode classNode : allClasses) {
      if (isController(classNode) || isService(classNode) || isRepository(classNode)) {
        participants.add(getSimpleClassName(classNode.name));
      }
    }

    for (ExternalCallInfo externalCall : externalCalls) {
      participants.add(getSimpleClassName(externalCall.getServiceName()));
    }

    participants.add("Database");

    for (String participant : participants) {
      if (participant.equals("Client")) {
        sb.append("actor ").append(participant).append("\n");
      } else if (participant.equals("Database")) {
        sb.append("database ").append(participant).append("\n");
      } else {
        sb.append("participant ").append(participant).append("\n");
      }
    }

    sb.append("\n");
  }

  private boolean isController(ClassNode classNode) {
    return hasAnnotation(classNode, "RestController") || hasAnnotation(classNode, "Controller");
  }

  private boolean isService(ClassNode classNode) {
    return hasAnnotation(classNode, "Service") || classNode.name.endsWith("Service")
        || classNode.name.endsWith("ServiceImpl");
  }

  private boolean isRepository(ClassNode classNode) {
    return hasAnnotation(classNode, "Repository") || classNode.name.endsWith("Repository");
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
}
