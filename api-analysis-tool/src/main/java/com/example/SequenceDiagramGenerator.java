package com.example;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

public class SequenceDiagramGenerator {
  private static final Logger logger = LoggerFactory.getLogger(SequenceDiagramGenerator.class);
  private final Map<String, ClassNode> implementationCache = new HashMap<>();

  private final Set<String> processedMethods = new HashSet<>();
  private final List<String> orderedParticipants = new ArrayList<>();
  private final Map<String, String> implToInterfaceMap = new HashMap<>();
  private final Map<String, String> classToHostMap = new HashMap<>();
  private final Map<String, Set<String>> methodToAnnotations = new HashMap<>();
  private final Map<String, String> clientToBaseUrlMap = new HashMap<>();
  private boolean useNamingConventions = true;
  private Set<String> priorityPackages = new HashSet<>();
  private int groupCounter = 0;
  private String currentClass;

  public String generateSequenceDiagram(List<ClassNode> allClasses) {
    logger.info("Starting sequence diagram generation for {} classes",
        allClasses.size());
    StringBuilder sb = new StringBuilder();
    initializeDiagram(sb);
    mapImplementationsToInterfaces(allClasses);
    findWebClientHostNames(allClasses);
    mapMethodAnnotations(allClasses);

    List<APIInfo> exposedApis = new APIInventoryExtractor().extractAPIs(allClasses);
    List<ExternalCallInfo> externalCalls = new ExternalCallScanner().findExternalCalls(allClasses);

    logger.info("Found {} exposed APIs and {} external calls",
        exposedApis.size(), externalCalls.size());

    appendParticipants(sb, allClasses, externalCalls);

    for (APIInfo api : exposedApis) {
      generateSequenceForAPI(sb, api, allClasses, externalCalls);
    }

    sb.append("@enduml");
    logger.info("Sequence diagram generation completed");
    return sb.toString();
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

  private void generateSequenceForAPI(StringBuilder sb, APIInfo api, List<ClassNode> allClasses,
      List<ExternalCallInfo> externalCalls) {
    sb.append("== ").append(api.getMethodName()).append(" ==\n");
    String controllerName = getSimpleClassName(getClassName(api.getMethodName()));
    sb.append("\"Client\" -> \"").append(controllerName).append("\" : ")
        .append(api.getHttpMethod()).append(" ").append(api.getPath()).append("\n");
    sb.append("activate \"").append(controllerName).append("\"\n");

    ClassNode controllerClass = findClassByName(allClasses, controllerName);
    if (controllerClass != null) {
      MethodNode method = findMethodByName(controllerClass, getMethodName(api.getMethodName()));
      if (method != null) {
        processedMethods.clear();
        processMethod(sb, method, allClasses, 1, controllerClass.name, new HashMap<>(), externalCalls);
      }
    }

    sb.append("\"").append(controllerName).append("\" --> \"Client\" : HTTP Response (")
        .append(api.getReturnType()).append(")\n");
    sb.append("deactivate \"").append(controllerName).append("\"\n\n");
  }

  private void processMethod(StringBuilder sb, MethodNode method, List<ClassNode> allClasses, int depth,
      String callerClass, Map<Integer, String> localVars, List<ExternalCallInfo> externalCalls) {
    if (depth > 10 || processedMethods.contains(callerClass + "." + method.name)) {
      return;
    }
    processedMethods.add(callerClass + "." + method.name);

    processMethodAnnotations(sb, callerClass, method);
    boolean isTransactional = isTransactionalMethod(callerClass, method);
    boolean isAsync = isAsyncMethod(callerClass, method);

    if (isTransactional)
      sb.append("group Transaction\n");
    if (isAsync)
      sb.append("group Asynchronous Operation\n");

    for (AbstractInsnNode insn : method.instructions) {
      if (insn instanceof VarInsnNode) {
        processVariableInstruction(sb, (VarInsnNode) insn, localVars, callerClass, method);
      } else if (insn instanceof MethodInsnNode) {
        processMethodCall(sb, (MethodInsnNode) insn, allClasses, depth, callerClass, localVars, externalCalls);
      } else if (insn instanceof JumpInsnNode) {
        processConditionalFlow(sb, (JumpInsnNode) insn, method, allClasses, depth, callerClass, localVars,
            externalCalls);
      } else if (insn instanceof LdcInsnNode) {
        processConstantInstruction(sb, (LdcInsnNode) insn, localVars, callerClass);
      } else if (insn instanceof InvokeDynamicInsnNode) {
        processLambdaOrMethodReference(sb, (InvokeDynamicInsnNode) insn, allClasses, depth, callerClass, externalCalls);
      } else if (insn instanceof LabelNode) {
        processLabelNode(sb, (LabelNode) insn, method);
      }
    }

    for (TryCatchBlockNode tryCatchBlock : method.tryCatchBlocks) {
      processTryCatchBlock(sb, tryCatchBlock, method, allClasses, depth, callerClass, localVars, externalCalls);
    }

    if (isAsync)
      sb.append("end\n");
    if (isTransactional)
      sb.append("end\n");

    // Check for database interactions
    if (callerClass.toLowerCase().contains("repository")) {
      processDatabaseInteraction(sb, getSimpleClassName(callerClass));
    }
  }

  private void processVariableInstruction(StringBuilder sb, VarInsnNode varInsn, Map<Integer, String> localVars,
      String callerClass, MethodNode methodNode) {
    String varName = getVariableName(varInsn.var, callerClass, methodNode);
    if (varInsn.getOpcode() == Opcodes.ASTORE) {
      localVars.put(varInsn.var, varName);
      sb.append("note over ").append(getInterfaceName(callerClass)).append(" : Store ").append(varName).append("\n");
    } else if (varInsn.getOpcode() == Opcodes.ALOAD) {
      sb.append("note over ").append(getInterfaceName(callerClass)).append(" : Load ").append(varName).append("\n");
    }
  }

  private String getVariableName(int index, String className, MethodNode methodNode) {
    if (methodNode.localVariables != null) {
      for (LocalVariableNode lvn : methodNode.localVariables) {
        if (lvn.index == index) {
          return lvn.name;
        }
      }
    }
    if (index < 0 || index >= methodNode.localVariables.size()) {
      logger.warn("Invalid variable index {} in method {} of class {}", index,
          methodNode.name, className);
      return "unknown_var_" + index;
    }
    // If LocalVariableTable is not available, use heuristics
    String simpleClassName = className.substring(className.lastIndexOf('/') + 1);

    // Check if it's 'this' reference
    if (index == 0 && !isStatic(methodNode.access)) {
      return "this";
    }

    // Check if it's a method parameter
    Type[] argumentTypes = Type.getArgumentTypes(methodNode.desc);
    if (index <= argumentTypes.length) {
      String paramType = getSimpleClassName(argumentTypes[index - 1].getClassName());
      return "param_" + paramType.toLowerCase() + "_" + index;
    }

    // Generate name based on variable type if available
    for (AbstractInsnNode insn : methodNode.instructions) {
      if (insn instanceof VarInsnNode && ((VarInsnNode) insn).var == index) {
        AbstractInsnNode nextInsn = insn.getNext();
        if (nextInsn instanceof MethodInsnNode) {
          MethodInsnNode methodInsn = (MethodInsnNode) nextInsn;
          String methodName = methodInsn.name;
          if (methodName.startsWith("set")) {
            return decapitalize(methodName.substring(3));
          }
        }
      }
    }

    // Default fallback
    return "var_" + simpleClassName + "_" + index;
  }

  private boolean isStatic(int access) {
    return (access & Opcodes.ACC_STATIC) != 0;
  }

  private String decapitalize(String str) {
    if (str == null || str.isEmpty()) {
      return str;
    }
    return Character.toLowerCase(str.charAt(0)) + str.substring(1);
  }

  private void processMethodAnnotations(StringBuilder sb, String callerClass, MethodNode method) {
    Set<String> annotations = methodToAnnotations.get(callerClass + "." + method.name);
    if (annotations != null && !annotations.isEmpty()) {
      sb.append("note over ").append(getInterfaceName(callerClass)).append(" : ")
          .append(String.join(", ", annotations)).append("\n");
    }
  }

  private boolean isTransactionalMethod(String callerClass, MethodNode method) {
    Set<String> annotations = methodToAnnotations.get(callerClass + "." + method.name);
    return annotations != null && annotations.contains("@Transactional");
  }

  private boolean isAsyncMethod(String callerClass, MethodNode method) {
    Set<String> annotations = methodToAnnotations.get(callerClass + "." + method.name);
    return annotations != null && annotations.contains("@Async");
  }

  private void processMethodCall(StringBuilder sb, MethodInsnNode methodInsn, List<ClassNode> allClasses, int depth,
      String callerClass, Map<Integer, String> localVars, List<ExternalCallInfo> externalCalls) {
    logger.debug("Processing method call: {}.{}", methodInsn.owner,
        methodInsn.name);
    String callerName = getInterfaceName(callerClass);
    String targetName = getInterfaceName(methodInsn.owner);

    if (methodInsn.owner.contains("WebClient")) {
      processWebClientCall(sb, methodInsn, callerName);
    } else {
      sb.append("\"").append(callerName).append("\" -> \"").append(targetName).append("\" : ")
          .append(methodInsn.name).append("()\n");
      sb.append("activate \"").append(targetName).append("\"\n");

      ClassNode targetClass = findImplementationClass(allClasses, methodInsn.owner);
      if (targetClass != null) {
        MethodNode targetMethod = findMethodByName(targetClass, methodInsn.name);
        if (targetMethod != null) {
          processMethod(sb, targetMethod, allClasses, depth + 1, targetClass.name, new HashMap<>(localVars),
              externalCalls);
        } else {
          logger.warn("Method {} not found in class {}", methodInsn.name,
              targetClass.name);
        }
      } else {
        logger.warn("Implementation not found for {}", methodInsn.owner);
      }

      sb.append("\"").append(targetName).append("\" --> \"").append(callerName).append("\" : return\n");
      sb.append("deactivate \"").append(targetName).append("\"\n");
    }
  }

  private void processWebClientCall(StringBuilder sb, MethodInsnNode methodInsn, String callerName) {
    String baseUrl = extractBaseUrl(methodInsn);
    String externalServiceName = getExternalServiceName(baseUrl);

    sb.append("\"").append(callerName).append("\" -> \"WebClient\" : ")
        .append(methodInsn.name).append("(").append(baseUrl).append(")\n");
    sb.append("\"WebClient\" -> \"").append(externalServiceName).append("\" : HTTP Request\n");
    sb.append("\"").append(externalServiceName).append("\" --> \"WebClient\" : HTTP Response\n");
    sb.append("\"WebClient\" --> \"").append(callerName).append("\" : return\n");
  }

  private void appendMethodParameters(StringBuilder sb, MethodInsnNode methodInsn, Map<Integer, String> localVars) {
    Type[] argumentTypes = Type.getArgumentTypes(methodInsn.desc);
    List<String> parameterNames = new ArrayList<>();
    for (int i = 0; i < argumentTypes.length; i++) {
      String paramName = localVars.getOrDefault(i, "param" + i);
      parameterNames.add(paramName + ": " + getSimplifiedTypeName(argumentTypes[i].getClassName()));
    }
    sb.append(String.join(", ", parameterNames));
  }

  private boolean isExternalCall(MethodInsnNode methodInsn, List<ExternalCallInfo> externalCalls) {
    return externalCalls.stream()
        .anyMatch(call -> call.getPurpose().equals(methodInsn.owner + "." + methodInsn.name));
  }

  private void processExternalApiCall(StringBuilder sb, MethodInsnNode methodInsn,
      List<ExternalCallInfo> externalCalls) {
    ExternalCallInfo callInfo = externalCalls.stream()
        .filter(call -> call.getPurpose().equals(methodInsn.owner + "." + methodInsn.name))
        .findFirst()
        .orElse(null);

    if (callInfo != null) {
      sb.append(getInterfaceName(methodInsn.owner)).append(" -> ")
          .append(callInfo.getUrl()).append(" : ")
          .append(callInfo.getHttpMethod()).append("\n");
      sb.append("activate ").append(callInfo.getUrl()).append("\n");
      sb.append(callInfo.getUrl()).append(" --> ")
          .append(getInterfaceName(methodInsn.owner)).append(" : response\n");
      sb.append("deactivate ").append(callInfo.getUrl()).append("\n");
    }
  }

  private void processLabelNode(StringBuilder sb, LabelNode labelNode, MethodNode method) {
    // Check if this label is a loop start
    for (AbstractInsnNode insn : method.instructions) {
      if (insn instanceof JumpInsnNode && ((JumpInsnNode) insn).label == labelNode) {
        if (insn.getOpcode() == Opcodes.GOTO) {
          sb.append("loop\n");
          return;
        }
      }
    }
  }

  private void processExternalApiCall(StringBuilder sb, ClassNode classNode, String methodName) {
    String hostName = classToHostMap.getOrDefault(classNode.name, "ExternalService");
    String httpMethod = extractHttpMethod(classNode, methodName);
    String path = extractPath(classNode, methodName);
    String responseModel = extractResponseModel(classNode, methodName);

    sb.append("group #LightBlue External API Call\n");
    sb.append(classNode.name).append(" -> ").append(hostName).append(" : ")
        .append(httpMethod).append(" ").append(path).append("\n");
    sb.append("activate ").append(hostName).append("\n");
    sb.append(hostName).append(" --> ").append(classNode.name)
        .append(" : return ").append(responseModel).append("\n");
    sb.append("deactivate ").append(hostName).append("\n");
    sb.append("end\n");
  }

  private void processReturnValue(StringBuilder sb, String className, String callerClass, String returnType) {
    String simplifiedClassName = getInterfaceName(className);
    String simplifiedCallerName = getInterfaceName(callerClass);
    String simplifiedReturnType = getSimplifiedTypeName(returnType);

    if (className.contains("WebClient")) {
      // Handle WebClient calls
      String baseUrl = clientToBaseUrlMap.getOrDefault(callerClass, "ExternalAPI");
      if (returnType.contains("Mono") || returnType.contains("Flux")) {
        sb.append("note right of ").append(baseUrl).append("\n")
            .append("Asynchronous operation\n")
            .append("Return type: ").append(simplifiedReturnType).append("\n")
            .append("end note\n");
      }
      sb.append(baseUrl).append(" --> ").append(simplifiedCallerName).append(" : return ")
          .append(simplifiedReturnType).append("\n");
      sb.append("deactivate ").append(baseUrl).append("\n");
    } else {
      // Handle normal method calls
      if (returnType.contains("Mono") || returnType.contains("Flux")) {
        sb.append("note right of ").append(simplifiedClassName).append("\n")
            .append("Asynchronous operation\n")
            .append("Return type: ").append(simplifiedReturnType).append("\n")
            .append("end note\n");
      }
      sb.append(simplifiedClassName).append(" --> ").append(simplifiedCallerName).append(" : return ")
          .append(simplifiedReturnType).append("\n");
      sb.append("deactivate ").append(simplifiedClassName).append("\n");
    }
  }

  private String getSimplifiedTypeName(String fullTypeName) {
    if (fullTypeName.equals("void")) {
      return "void";
    }
    if (fullTypeName.contains("<")) {
      String baseType = fullTypeName.substring(0, fullTypeName.indexOf('<'));
      String paramType = fullTypeName.substring(fullTypeName.indexOf('<') + 1, fullTypeName.lastIndexOf('>'));
      return getSimpleClassName(baseType) + "<" + getSimpleClassName(paramType) + ">";
    }
    return getSimpleClassName(fullTypeName);
  }

  private void processConditionalFlow(StringBuilder sb, JumpInsnNode jumpInsn, MethodNode method,
      List<ClassNode> allClasses, int depth, String callerClass, Map<Integer, String> localVars,
      List<ExternalCallInfo> externalCalls) {
    String callerName = getInterfaceName(callerClass);
    sb.append("alt ").append(getConditionDescription(jumpInsn)).append("\n");

    groupCounter++;
    String groupName = "group_" + groupCounter;
    sb.append("group #LightYellow ").append(groupName).append("\n");

    // Process the "if" block
    AbstractInsnNode currentInsn = jumpInsn.getNext();
    while (currentInsn != null && currentInsn != jumpInsn.label) {
      if (currentInsn instanceof MethodInsnNode) {
        processMethodCall(sb, (MethodInsnNode) currentInsn, allClasses, depth + 1, callerClass, localVars,
            externalCalls);
      }
      currentInsn = currentInsn.getNext();
    }

    sb.append("end\n");
    sb.append("else\n");

    groupCounter++;
    String elseGroupName = "group_" + groupCounter;
    sb.append("group #LightCyan ").append(elseGroupName).append("\n");

    // Process the "else" block
    while (currentInsn != null
        && !(currentInsn instanceof LabelNode && ((LabelNode) currentInsn).getLabel() == jumpInsn.label.getLabel())) {
      if (currentInsn instanceof MethodInsnNode) {
        processMethodCall(sb, (MethodInsnNode) currentInsn, allClasses, depth + 1, callerClass, localVars,
            externalCalls);
      }
      currentInsn = currentInsn.getNext();
    }

    sb.append("end\n");
    sb.append("end\n");
  }

  private String getConditionDescription(JumpInsnNode jumpInsn) {
    switch (jumpInsn.getOpcode()) {
      case Opcodes.IFEQ:
        return "if equals";
      case Opcodes.IFNE:
        return "if not equals";
      case Opcodes.IFLT:
        return "if less than";
      case Opcodes.IFGE:
        return "if greater than or equals";
      case Opcodes.IFGT:
        return "if greater than";
      case Opcodes.IFLE:
        return "if less than or equals";
      default:
        return "condition";
    }
  }

  private void mapMethodAnnotations(List<ClassNode> allClasses) {
    for (ClassNode classNode : allClasses) {
      for (MethodNode method : classNode.methods) {
        if (method.visibleAnnotations != null) {
          Set<String> annotations = new HashSet<>();
          for (AnnotationNode annotation : method.visibleAnnotations) {
            String annotationName = Type.getType(annotation.desc).getClassName();
            annotations.add("@" + annotationName.substring(annotationName.lastIndexOf('.') + 1));
          }
          methodToAnnotations.put(classNode.name + "." + method.name, annotations);
        }
      }
    }
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
        classToHostMap.put(classNode.name, getExternalServiceName(baseUrl));
      }
    }
  }

  private void appendParticipants(StringBuilder sb, List<ClassNode> allClasses, List<ExternalCallInfo> externalCalls) {
    Set<String> controllers = new HashSet<>();
    Set<String> services = new HashSet<>();
    Set<String> repositories = new HashSet<>();
    Set<String> externalServices = new HashSet<>();

    for (ClassNode classNode : allClasses) {
      String className = getSimpleClassName(classNode.name);
      if (className.endsWith("Controller")) {
        controllers.add(className);
      } else if (className.endsWith("Service") || className.endsWith("ServiceImpl") || isServiceAnnotated(classNode)) {
        services.add(getInterfaceName(classNode.name));
      } else if (className.endsWith("Repository")) {
        repositories.add(className);
      }

      String hostName = classToHostMap.get(classNode.name);
      if (hostName != null) {
        externalServices.add(hostName);
      }
    }

    for (ExternalCallInfo externalCall : externalCalls) {
      externalServices.add(getExternalServiceName(externalCall.getUrl()));
    }

    orderedParticipants.add("Client");
    sb.append("actor Client\n");

    orderedParticipants.addAll(controllers);
    for (String controller : controllers) {
      sb.append("participant ").append(controller).append("\n");
    }

    orderedParticipants.addAll(services);
    for (String service : services) {
      sb.append("participant ").append(service).append("\n");
    }

    orderedParticipants.addAll(externalServices);
    for (String externalService : externalServices) {
      sb.append("participant ").append(externalService).append("\n");
    }

    orderedParticipants.addAll(repositories);
    for (String repository : repositories) {
      String databaseName = getDatabaseName(repository);
      orderedParticipants.add(databaseName);
      sb.append("database ").append(databaseName).append("\n");
    }

    sb.append("\n");
  }

  private boolean isServiceAnnotated(ClassNode classNode) {
    if (classNode.visibleAnnotations != null) {
      for (AnnotationNode annotation : classNode.visibleAnnotations) {
        if (annotation.desc.equals("Lorg/springframework/stereotype/Service;")) {
          return true;
        }
      }
    }
    return false;
  }

  private String getInterfaceName(String className) {
    if (className == null) {
      return "UnknownClass";
    }
    String interfaceName = implToInterfaceMap.get(className);
    return interfaceName != null ? getSimpleClassName(interfaceName) : getSimpleClassName(className);
  }

  private String getExternalServiceName(String url) {
    try {
      java.net.URL parsedUrl = new java.net.URL(url);
      return parsedUrl.getHost().replaceAll("\\.", "_");
    } catch (java.net.MalformedURLException e) {
      return "ExternalService";
    }
  }

  private String getDatabaseName(String repositoryName) {
    return repositoryName.replace("Repository", "DB");
  }

  private String extractBaseUrl(ClassNode classNode) {
    for (MethodNode method : classNode.methods) {
      if (method.name.equals("<init>")) {
        for (AbstractInsnNode insn : method.instructions) {
          if (insn instanceof LdcInsnNode) {
            LdcInsnNode ldcInsn = (LdcInsnNode) insn;
            if (ldcInsn.cst instanceof String && ((String) ldcInsn.cst).startsWith("http")) {
              return (String) ldcInsn.cst;
            }
          }
        }
      }
    }
    return null;
  }

  private String extractHttpMethod(ClassNode classNode, String methodName) {
    MethodNode method = findMethodByName(classNode, methodName);
    if (method != null) {
      for (AbstractInsnNode insn : method.instructions) {
        if (insn instanceof MethodInsnNode) {
          MethodInsnNode methodInsn = (MethodInsnNode) insn;
          if (methodInsn.name.equals("get") || methodInsn.name.equals("post") ||
              methodInsn.name.equals("put") || methodInsn.name.equals("delete")) {
            return methodInsn.name.toUpperCase();
          }
        }
      }
    }
    return "UNKNOWN";
  }

  private String extractPath(ClassNode classNode, String methodName) {
    MethodNode method = findMethodByName(classNode, methodName);
    if (method != null) {
      for (AbstractInsnNode insn : method.instructions) {
        if (insn instanceof LdcInsnNode) {
          LdcInsnNode ldcInsn = (LdcInsnNode) insn;
          if (ldcInsn.cst instanceof String && ((String) ldcInsn.cst).startsWith("/")) {
            return (String) ldcInsn.cst;
          }
        }
      }
    }
    return "/unknown-path";
  }

  private String extractResponseModel(ClassNode classNode, String methodName) {
    MethodNode method = findMethodByName(classNode, methodName);
    if (method != null) {
      String returnType = Type.getReturnType(method.desc).getClassName();
      if (returnType.contains("Mono")) {
        // Extract the type parameter of Mono
        return extractTypeParameter(returnType);
      }
      return returnType;
    }
    return "Unknown";
  }

  private String extractTypeParameter(String type) {
    int start = type.indexOf('<');
    int end = type.lastIndexOf('>');
    if (start != -1 && end != -1) {
      return type.substring(start + 1, end);
    }
    return type;
  }

  private ClassNode findImplementationClass(List<ClassNode> allClasses, String interfaceName) {
    logger.debug("Searching for implementation of interface/class: {}", interfaceName);

    // Check cache first
    if (implementationCache.containsKey(interfaceName)) {
      ClassNode cachedImplementation = implementationCache.get(interfaceName);
      if (cachedImplementation != null) {
        logger.debug("Found cached implementation for {}: {}", interfaceName, cachedImplementation.name);
        return cachedImplementation;
      } else {
        logger.debug("Cached implementation for {} is null", interfaceName);
      }
    }

    ClassNode result = findImplementationClassInternal(allClasses, interfaceName);

    // Cache the result
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
    List<ClassNode> directImplementations = allClasses.stream()
        .filter(classNode -> classNode.interfaces.contains(interfaceName))
        .collect(Collectors.toList());

    if (!directImplementations.isEmpty()) {
      if (directImplementations.size() > 1) {
        logger.warn("Multiple direct implementations found for {}: {}", interfaceName,
            directImplementations.stream().map(cn -> cn.name).collect(Collectors.joining(", ")));
      }
      logger.info("Found direct implementation for {}: {}", interfaceName, directImplementations.get(0).name);
      return directImplementations.get(0);
    }

    // Step 3: Look for indirect implementations (through interface inheritance)
    for (ClassNode classNode : allClasses) {
      if (isIndirectImplementation(classNode, interfaceName, allClasses, new HashSet<>())) {
        logger.info("Found indirect implementation for {}: {}", interfaceName, classNode.name);
        return classNode;
      }
    }

    // Step 4: Handle Spring Data repositories and other dynamic proxies
    if (interfaceName.contains("Repository") || interfaceName.endsWith("Dao")) {
      logger.info("Assuming Spring Data repository or DAO for: {}", interfaceName);
      return concreteClass; // Return the interface itself
    }

    // Step 5: Look for abstract class implementations
    List<ClassNode> abstractImplementations = allClasses.stream()
        .filter(classNode -> isSubclassOf(classNode, interfaceName, allClasses))
        .collect(Collectors.toList());

    if (!abstractImplementations.isEmpty()) {
      if (abstractImplementations.size() > 1) {
        logger.warn("Multiple abstract implementations found for {}: {}", interfaceName,
            abstractImplementations.stream().map(cn -> cn.name).collect(Collectors.joining(", ")));
      }
      logger.info("Found implementation through abstract class for {}: {}", interfaceName,
          abstractImplementations.get(0).name);
      return abstractImplementations.get(0);
    }

    // Step 6: Look for classes with similar names (potential naming convention
    // match)
    String simpleInterfaceName = getSimpleClassName(interfaceName);
    List<ClassNode> potentialMatches = allClasses.stream()
        .filter(classNode -> getSimpleClassName(classNode.name).contains(simpleInterfaceName))
        .collect(Collectors.toList());

    if (!potentialMatches.isEmpty()) {
      logger.warn("No exact implementation found for {}. Using potential match based on naming convention: {}",
          interfaceName, potentialMatches.get(0).name);
      return potentialMatches.get(0);
    }

    logger.warn("No implementation found for {}. Using the interface/class itself.", interfaceName);
    return concreteClass; // Return the interface/class itself if no implementation is found
  }

  private boolean isInterface(ClassNode classNode) {
    return (classNode.access & Opcodes.ACC_INTERFACE) != 0;
  }

  private boolean isIndirectImplementation(ClassNode classNode, String targetInterface, List<ClassNode> allClasses,
      Set<String> visited) {
    if (visited.contains(classNode.name)) {
      return false;
    }
    visited.add(classNode.name);

    logger.trace("Checking indirect implementation: {} for target: {}", classNode.name, targetInterface);

    for (String implementedInterface : classNode.interfaces) {
      if (implementedInterface.equals(targetInterface)) {
        logger.debug("Found indirect implementation: {} implements {}", classNode.name, targetInterface);
        return true;
      }
      ClassNode interfaceNode = findClassByName(allClasses, implementedInterface);
      if (interfaceNode != null && isIndirectImplementation(interfaceNode, targetInterface, allClasses, visited)) {
        logger.debug("Found indirect implementation: {} extends {} which implements {}",
            classNode.name, implementedInterface, targetInterface);
        return true;
      }
    }
    return false;
  }

  private boolean isSubclassOf(ClassNode classNode, String superClassName, List<ClassNode> allClasses) {
    logger.trace("Checking if {} is subclass of {}", classNode.name, superClassName);
    while (classNode != null) {
      if (classNode.superName.equals(superClassName)) {
        logger.debug("Found subclass relationship: {} extends {}", classNode.name, superClassName);
        return true;
      }
      classNode = findClassByName(allClasses, classNode.superName);
    }
    return false;
  }

  private ClassNode findClassByName(List<ClassNode> classes, String name) {
    return classes.stream()
        .filter(c -> getSimpleClassName(c.name).equals(name))
        .findFirst()
        .orElse(null);
  }

  private MethodNode findMethodByName(ClassNode classNode, String name) {
    return classNode.methods.stream()
        .filter(m -> m.name.equals(name))
        .findFirst()
        .orElse(null);
  }

  private String getClassName(String fullMethodName) {
    int lastDotIndex = fullMethodName.lastIndexOf('.');
    if (lastDotIndex == -1) {
      return fullMethodName;
    }
    return fullMethodName.substring(0, lastDotIndex);
  }

  private String getMethodName(String fullMethodName) {
    int lastDotIndex = fullMethodName.lastIndexOf('.');
    if (lastDotIndex == -1) {
      return fullMethodName;
    }
    return fullMethodName.substring(lastDotIndex + 1);
  }

  private String getSimpleClassName(String fullClassName) {
    int lastSlashIndex = fullClassName.lastIndexOf('/');
    if (lastSlashIndex == -1) {
      return fullClassName;
    }
    return fullClassName.substring(lastSlashIndex + 1);
  }

  private void processDatabaseInteraction(StringBuilder sb, String repositoryName) {
    String databaseName = getDatabaseName(repositoryName);
    sb.append(repositoryName).append(" -> ").append(databaseName).append(" : execute query\n");
    sb.append("activate ").append(databaseName).append("\n");
    sb.append(databaseName).append(" --> ").append(repositoryName).append(" : return data\n");
    sb.append("deactivate ").append(databaseName).append("\n");
  }

  private void processConstantInstruction(StringBuilder sb, LdcInsnNode ldcInsn, Map<Integer, String> localVars,
      String callerClass) {
    String constantValue = ldcInsn.cst.toString();
    sb.append("note over ").append(getInterfaceName(callerClass)).append(" : Load constant: ").append(constantValue)
        .append("\n");
  }

  private void processLambdaOrMethodReference(StringBuilder sb, InvokeDynamicInsnNode insn, List<ClassNode> allClasses,
      int depth, String callerClass, List<ExternalCallInfo> externalCalls) {
    String callerName = getInterfaceName(callerClass);
    String lambdaName = insn.name;
    String lambdaDesc = insn.desc;

    sb.append("note over ").append(callerName).append("\n");
    sb.append("Lambda or Method Reference: ").append(lambdaName).append("\n");
    sb.append("Descriptor: ").append(lambdaDesc).append("\n");
    sb.append("end note\n");

    // Try to find the implemented method
    String implementedMethodName = extractImplementedMethodName(insn);
    if (implementedMethodName != null) {
      ClassNode targetClass = findClassByName(allClasses, callerClass);
      if (targetClass != null) {
        MethodNode targetMethod = findMethodByName(targetClass, implementedMethodName);
        if (targetMethod != null) {
          processMethod(sb, targetMethod, allClasses, depth + 1, callerClass, new HashMap<>(), externalCalls);
        }
      }
    }
  }

  private String extractImplementedMethodName(InvokeDynamicInsnNode insn) {
    if (insn.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory") && insn.bsm.getName().equals("metafactory")) {
      Object[] bsmArgs = insn.bsmArgs;
      if (bsmArgs.length >= 2 && bsmArgs[1] instanceof Handle) {
        Handle implementedMethod = (Handle) bsmArgs[1];
        return implementedMethod.getName();
      }
    }
    return null;
  }

  private void processTryCatchBlock(StringBuilder sb, TryCatchBlockNode tryCatchBlock, MethodNode method,
      List<ClassNode> allClasses, int depth, String callerClass, Map<Integer, String> localVars,
      List<ExternalCallInfo> externalCalls) {
    sb.append("group #LightGray Try\n");

    AbstractInsnNode currentInsn = method.instructions.get(method.instructions.indexOf(tryCatchBlock.start));
    while (currentInsn != tryCatchBlock.end) {
      if (currentInsn instanceof MethodInsnNode) {
        processMethodCall(sb, (MethodInsnNode) currentInsn, allClasses, depth + 1, callerClass, localVars,
            externalCalls);
      }
      currentInsn = currentInsn.getNext();
    }

    sb.append("end\n");
    sb.append("group #LightCoral Catch ").append(getSimpleClassName(tryCatchBlock.type)).append("\n");

    currentInsn = method.instructions.get(method.instructions.indexOf(tryCatchBlock.handler));
    while (!(currentInsn instanceof LabelNode)) {
      if (currentInsn instanceof MethodInsnNode) {
        processMethodCall(sb, (MethodInsnNode) currentInsn, allClasses, depth + 1, callerClass, localVars,
            externalCalls);
      }
      currentInsn = currentInsn.getNext();
    }

    sb.append("end\n");
  }

  private void processWebClientInitialization(StringBuilder sb, MethodInsnNode methodInsn, String callerClass) {
    String callerName = getInterfaceName(callerClass);
    String baseUrl = extractBaseUrl(methodInsn);

    if (baseUrl != null) {
      clientToBaseUrlMap.put(callerClass, baseUrl);
      String externalServiceName = getExternalServiceName(baseUrl);

      if (!orderedParticipants.contains(externalServiceName)) {
        orderedParticipants.add(externalServiceName);
        sb.append("participant ").append(externalServiceName).append(" as External API\n");
      }

      sb.append(callerName).append(" -> WebClient : baseUrl(")
          .append(baseUrl).append(")\n");
      logger.info("WebClient initialized for {} with base URL: {}", callerClass,
          baseUrl);
    } else {
      sb.append("note over ").append(callerName).append(" : Initialize WebClient (unknown URL)\n");
      logger.warn("Unable to extract base URL for WebClient initialization in class: {}", callerClass);
    }
  }

  private String extractBaseUrl(MethodInsnNode methodInsn) {
    AbstractInsnNode currentInsn = methodInsn.getPrevious();
    String baseUrl = null;
    int depth = 0;

    while (currentInsn != null && depth < 10) {
      if (currentInsn instanceof LdcInsnNode) {
        LdcInsnNode ldcInsn = (LdcInsnNode) currentInsn;
        if (ldcInsn.cst instanceof String) {
          String potentialUrl = (String) ldcInsn.cst;
          if (isValidUrl(potentialUrl)) {
            return potentialUrl;
          }
        }
      } else if (currentInsn instanceof MethodInsnNode) {
        MethodInsnNode prevMethodInsn = (MethodInsnNode) currentInsn;
        if (prevMethodInsn.name.equals("baseUrl") || prevMethodInsn.name.equals("create")) {
          // If we encounter a baseUrl() or create() method call, check its argument
          AbstractInsnNode argInsn = prevMethodInsn.getPrevious();
          if (argInsn instanceof LdcInsnNode) {
            LdcInsnNode ldcInsn = (LdcInsnNode) argInsn;
            if (ldcInsn.cst instanceof String) {
              String potentialUrl = (String) ldcInsn.cst;
              if (isValidUrl(potentialUrl)) {
                baseUrl = potentialUrl;
                break;
              }
            }
          }
        }
      } else if (currentInsn instanceof FieldInsnNode) {
        FieldInsnNode fieldInsn = (FieldInsnNode) currentInsn;
        if (fieldInsn.name.toLowerCase().contains("url") || fieldInsn.name.toLowerCase().contains("endpoint")) {
          // If we encounter a field that might contain the URL, we'll use its name as a
          // placeholder
          baseUrl = "${" + fieldInsn.name + "}";
          break;
        }
      }
      currentInsn = currentInsn.getPrevious();
      depth++;
    }

    if (baseUrl == null) {
      // If we couldn't find a base URL, return a generic placeholder
      baseUrl = "${API_BASE_URL}";
    }

    return baseUrl;
  }

  private boolean isValidUrl(String url) {
    try {
      new java.net.URL(url);
      return true;
    } catch (java.net.MalformedURLException e) {
      return false;
    }
  }

  public String combineDiagrams(List<String> diagrams) {
    StringBuilder combined = new StringBuilder("@startuml\n");

    // Combine participants
    Set<String> allParticipants = diagrams.stream()
        .flatMap(diagram -> extractParticipants(diagram).stream())
        .collect(Collectors.toCollection(LinkedHashSet::new));

    allParticipants.forEach(combined::append);

    // Combine sequences
    diagrams.stream()
        .map(this::extractSequence)
        .forEach(sequence -> combined.append(sequence).append("\n"));

    combined.append("@enduml");
    return combined.toString();
  }

  private List<String> extractParticipants(String diagram) {
    List<String> participants = new ArrayList<>();
    String[] lines = diagram.split("\n");
    for (String line : lines) {
      if (line.startsWith("participant") || line.startsWith("actor") || line.startsWith("database")) {
        participants.add(line);
      }
    }
    return participants;
  }

  private String extractSequence(String diagram) {
    StringBuilder sequence = new StringBuilder();
    boolean inSequence = false;
    String[] lines = diagram.split("\n");
    for (String line : lines) {
      if (line.equals("@startuml")) {
        inSequence = true;
        continue;
      }
      if (line.equals("@enduml")) {
        break;
      }
      if (inSequence && !line.startsWith("participant") && !line.startsWith("actor") && !line.startsWith("database")) {
        sequence.append(line).append("\n");
      }
    }
    return sequence.toString();
  }

  public void setUseNamingConventions(boolean useNamingConventions) {
    this.useNamingConventions = useNamingConventions;
  }

  public void addPriorityPackage(String packageName) {
    priorityPackages.add(packageName);
  }

  private boolean hasSpringAnnotation(ClassNode classNode) {
    return classNode.visibleAnnotations != null && classNode.visibleAnnotations.stream()
        .anyMatch(an -> an.desc.contains("org/springframework"));
  }
}