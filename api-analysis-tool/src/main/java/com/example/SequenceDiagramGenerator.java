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
  private int groupCounter = 0;

  // Configuration options
  private boolean useNamingConventions = true;
  private Set<String> priorityPackages = new HashSet<>();
  private int maxDepth = 10;

  // Custom rules
  private List<ImplementationMatcher> customRules = new ArrayList<>();

  @FunctionalInterface
  public interface ImplementationMatcher {
    boolean matches(ClassNode classNode, String interfaceName);
  }

  // Configuration methods
  public void setUseNamingConventions(boolean useNamingConventions) {
    this.useNamingConventions = useNamingConventions;
  }

  public void addPriorityPackage(String packageName) {
    priorityPackages.add(packageName);
  }

  public void setMaxDepth(int maxDepth) {
    this.maxDepth = maxDepth;
  }

  public void addCustomRule(ImplementationMatcher matcher) {
    customRules.add(matcher);
  }

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
        processMethod(sb, method, allClasses, 1, controllerClass.name, new HashMap<>(), externalCalls, new HashSet<>());
      }
    }

    sb.append("\"").append(controllerName).append("\" --> \"Client\" : HTTP Response (")
        .append(api.getReturnType()).append(")\n");
    sb.append("deactivate \"").append(controllerName).append("\"\n\n");
  }

  private void processMethod(StringBuilder sb, MethodNode method, List<ClassNode> allClasses, int depth,
      String callerClass, Map<Integer, String> localVars, List<ExternalCallInfo> externalCalls,
      Set<String> callStack) {
    if (depth > maxDepth || callStack.contains(callerClass + "." + method.name)) {
      logger.warn("Possible circular dependency or max depth reached: {}.{}", callerClass, method.name);
      return;
    }
    callStack.add(callerClass + "." + method.name);

    processedMethods.add(callerClass + "." + method.name);

    processMethodAnnotations(sb, callerClass, method);
    boolean isTransactional = isTransactionalMethod(callerClass, method);
    boolean isAsync = isAsyncMethod(callerClass, method);

    if (isTransactional)
      sb.append("group Transaction\n");
    if (isAsync)
      sb.append("group Asynchronous Operation\n");

    for (AbstractInsnNode insn : method.instructions) {
      processInstruction(sb, insn, method, allClasses, depth, callerClass, localVars, externalCalls, callStack);
    }

    for (TryCatchBlockNode tryCatchBlock : method.tryCatchBlocks) {
      processTryCatchBlock(sb, tryCatchBlock, method, allClasses, depth, callerClass, localVars, externalCalls,
          callStack);
    }

    if (isAsync)
      sb.append("end\n");
    if (isTransactional)
      sb.append("end\n");

    // Check for database interactions
    if (callerClass.toLowerCase().contains("repository")) {
      processDatabaseInteraction(sb, getSimpleClassName(callerClass));
    }

    callStack.remove(callerClass + "." + method.name);
  }

  private void processInstruction(StringBuilder sb, AbstractInsnNode insn, MethodNode method,
      List<ClassNode> allClasses, int depth,
      String callerClass, Map<Integer, String> localVars, List<ExternalCallInfo> externalCalls, Set<String> callStack) {

    switch (insn.getOpcode()) {
      case Opcodes.ILOAD:
      case Opcodes.LLOAD:
      case Opcodes.FLOAD:
      case Opcodes.DLOAD:
      case Opcodes.ALOAD:
        processVariableLoad(sb, (VarInsnNode) insn, method, callerClass, localVars);
        break;
      case Opcodes.ISTORE:
      case Opcodes.LSTORE:
      case Opcodes.FSTORE:
      case Opcodes.DSTORE:
      case Opcodes.ASTORE:
        processVariableStore(sb, (VarInsnNode) insn, method, callerClass, localVars);
        break;
      case Opcodes.INVOKEVIRTUAL:
      case Opcodes.INVOKESPECIAL:
      case Opcodes.INVOKESTATIC:
      case Opcodes.INVOKEINTERFACE:
        processMethodCall(sb, (MethodInsnNode) insn, allClasses, depth, callerClass, localVars, externalCalls,
            callStack);
        break;
      case Opcodes.NEW:
        processObjectCreation(sb, (TypeInsnNode) insn, callerClass);
        break;
      case Opcodes.GETFIELD:
      case Opcodes.GETSTATIC:
        processFieldAccess(sb, (FieldInsnNode) insn, callerClass, true);
        break;
      case Opcodes.PUTFIELD:
      case Opcodes.PUTSTATIC:
        processFieldAccess(sb, (FieldInsnNode) insn, callerClass, false);
        break;
      case Opcodes.IFEQ:
      case Opcodes.IFNE:
      case Opcodes.IFLT:
      case Opcodes.IFGE:
      case Opcodes.IFGT:
      case Opcodes.IFLE:
      case Opcodes.IF_ICMPEQ:
      case Opcodes.IF_ICMPNE:
      case Opcodes.IF_ICMPLT:
      case Opcodes.IF_ICMPGE:
      case Opcodes.IF_ICMPGT:
      case Opcodes.IF_ICMPLE:
      case Opcodes.IF_ACMPEQ:
      case Opcodes.IF_ACMPNE:
      case Opcodes.IFNULL:
      case Opcodes.IFNONNULL:
        processConditionalFlow(sb, (JumpInsnNode) insn, method, allClasses, depth, callerClass, localVars,
            externalCalls, callStack);
        break;
      case Opcodes.ATHROW:
        processExceptionThrow(sb, callerClass);
        break;
      default:
        // For other instructions, we might want to add a generic note or simply ignore
        // them
        logger.debug("Unhandled instruction: {} in {}.{}", insn.getOpcode(), callerClass, method.name);
    }
  }

  private void processVariableLoad(StringBuilder sb, VarInsnNode varInsn, MethodNode method, String callerClass,
      Map<Integer, String> localVars) {
    String varName = getVariableName(varInsn.var, method);
    String varType = getVariableType(varInsn.var, method);
    String fullVarInfo = varName + ":" + getDetailedType(varType);
    localVars.put(varInsn.var, fullVarInfo);

    String callerName = getInterfaceName(callerClass);
    logger.debug("Load variable: {} in method: {}.{}", fullVarInfo, callerClass, method.name);

    sb.append("note over ").append(callerName).append(" : Load ").append(fullVarInfo).append("\n");
  }

  private void processVariableStore(StringBuilder sb, VarInsnNode varInsn, MethodNode method, String callerClass,
      Map<Integer, String> localVars) {
    String varName = getVariableName(varInsn.var, method);
    String varType = getVariableType(varInsn.var, method);
    String fullVarInfo = varName + ":" + getDetailedType(varType);
    localVars.put(varInsn.var, fullVarInfo);

    String callerName = getInterfaceName(callerClass);
    logger.debug("Store variable: {} in method: {}.{}", fullVarInfo, callerClass, method.name);

    sb.append("note over ").append(callerName).append(" : Store ").append(fullVarInfo).append("\n");
  }

  private void processObjectCreation(StringBuilder sb, TypeInsnNode typeInsn, String callerClass) {
    String callerName = getInterfaceName(callerClass);
    String objectType = getDetailedType(typeInsn.desc);
    logger.debug("Object creation: {} in class: {}", objectType, callerClass);

    sb.append("note over ").append(callerName).append(" : Create new ").append(objectType).append("\n");
  }

  private void processFieldAccess(StringBuilder sb, FieldInsnNode fieldInsn, String callerClass, boolean isGet) {
    String callerName = getInterfaceName(callerClass);
    String fieldType = getDetailedType(fieldInsn.desc);
    String operation = isGet ? "Get" : "Set";
    logger.debug("{} field: {}.{} : {} in class: {}", operation, fieldInsn.owner, fieldInsn.name, fieldType,
        callerClass);

    sb.append("note over ").append(callerName).append(" : ").append(operation).append(" ")
        .append(fieldInsn.name).append(":").append(fieldType).append(" from ")
        .append(getSimpleClassName(fieldInsn.owner)).append("\n");
  }

  private void processExceptionThrow(StringBuilder sb, String callerClass) {
    String callerName = getInterfaceName(callerClass);
    logger.debug("Exception thrown in class: {}", callerClass);

    sb.append("note over ").append(callerName).append(" : Throw exception\n");
  }

  private String getVariableName(int index, MethodNode method) {
    if (method.localVariables != null) {
      for (LocalVariableNode lvn : method.localVariables) {
        if (lvn.index == index) {
          return lvn.name;
        }
      }
    }
    return "local" + index;
  }

  private String getVariableType(int index, MethodNode method) {
    if (method.localVariables != null) {
      for (LocalVariableNode lvn : method.localVariables) {
        if (lvn.index == index) {
          return lvn.desc;
        }
      }
    }
    return "Ljava/lang/Object;";
  }

  private String getDetailedType(String desc) {
    if (desc.startsWith("L")) {
      String className = desc.substring(1, desc.length() - 1).replace('/', '.');
      if (className.contains("<")) {
        // Handle generic types
        int startIndex = className.indexOf('<');
        int endIndex = className.lastIndexOf('>');
        String baseType = className.substring(0, startIndex);
        String genericPart = className.substring(startIndex + 1, endIndex);
        String[] genericTypes = genericPart.split(";");
        StringBuilder sb = new StringBuilder(baseType).append('<');
        for (int i = 0; i < genericTypes.length; i++) {
          if (i > 0)
            sb.append(", ");
          sb.append(getDetailedType(genericTypes[i] + ";"));
        }
        sb.append('>');
        return sb.toString();
      }
      return className;
    } else if (desc.startsWith("[")) {
      return getDetailedType(desc.substring(1)) + "[]";
    } else {
      switch (desc) {
        case "Z":
          return "boolean";
        case "B":
          return "byte";
        case "C":
          return "char";
        case "D":
          return "double";
        case "F":
          return "float";
        case "I":
          return "int";
        case "J":
          return "long";
        case "S":
          return "short";
        case "V":
          return "void";
        default:
          return desc;
      }
    }
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
      String callerClass, Map<Integer, String> localVars, List<ExternalCallInfo> externalCalls,
      Set<String> callStack) {

    String callerName = getInterfaceName(callerClass);
    String targetName = getInterfaceName(methodInsn.owner);

    // Get parameter types and names
    Type[] argumentTypes = Type.getArgumentTypes(methodInsn.desc);
    String[] parameterTypes = new String[argumentTypes.length];
    String[] parameterNames = new String[argumentTypes.length];

    // Extract actual parameter values from the stack
    List<String> actualParams = extractActualParameters(methodInsn, localVars);

    StringBuilder methodCallSb = new StringBuilder(methodInsn.name).append("(");
    for (int i = 0; i < argumentTypes.length; i++) {
      if (i > 0)
        methodCallSb.append(", ");
      String paramType = getDetailedType(argumentTypes[i].getDescriptor());
      String paramValue = i < actualParams.size() ? actualParams.get(i) : "?";
      parameterTypes[i] = paramType;
      parameterNames[i] = paramValue;
      methodCallSb.append(paramType).append(" ").append(paramValue);
    }
    methodCallSb.append(")");

    // Get return type
    Type returnType = Type.getReturnType(methodInsn.desc);
    String returnTypeName = getDetailedType(returnType.getDescriptor());

    logger.debug("Method call: {}.{} -> {}.{}", callerClass, methodInsn.name, methodInsn.owner, methodCallSb);
    logger.debug("Return type: {}", returnTypeName);

    sb.append("\"").append(callerName).append("\" -> \"").append(targetName).append("\" : ")
        .append(methodCallSb).append("\n");
    sb.append("activate \"").append(targetName).append("\"\n");

    ClassNode targetClass = findImplementationClass(allClasses, methodInsn.owner);
    if (targetClass != null) {
      MethodNode targetMethod = findMethodByName(targetClass, methodInsn.name);
      if (targetMethod != null) {
        logger.debug("Processing method body: {}.{}", targetClass.name, targetMethod.name);
        processMethod(sb, targetMethod, allClasses, depth + 1, targetClass.name, new HashMap<>(localVars),
            externalCalls, new HashSet<>(callStack));
      } else {
        logger.warn("Method {} not found in class {}", methodInsn.name, targetClass.name);
      }
    } else {
      logger.warn("Implementation not found for {}", methodInsn.owner);
    }

    sb.append("\"").append(targetName).append("\" --> \"").append(callerName).append("\" : return ")
        .append(returnTypeName).append("\n");
    sb.append("deactivate \"").append(targetName).append("\"\n");
  }

  private List<String> extractActualParameters(MethodInsnNode methodInsn, Map<Integer, String> localVars) {
    List<String> params = new ArrayList<>();
    AbstractInsnNode currentInsn = methodInsn.getPrevious();
    int paramCount = Type.getArgumentTypes(methodInsn.desc).length;

    while (currentInsn != null && params.size() < paramCount) {
      if (currentInsn instanceof VarInsnNode) {
        VarInsnNode varInsn = (VarInsnNode) currentInsn;
        String varInfo = localVars.get(varInsn.var);
        if (varInfo != null) {
          params.add(0, varInfo.split(":")[0]); // Add variable name
        } else {
          params.add(0, "local" + varInsn.var);
        }
      } else if (currentInsn instanceof LdcInsnNode) {
        LdcInsnNode ldcInsn = (LdcInsnNode) currentInsn;
        params.add(0, ldcInsn.cst.toString());
      } else if (currentInsn instanceof MethodInsnNode) {
        MethodInsnNode prevMethodInsn = (MethodInsnNode) currentInsn;
        params.add(0, prevMethodInsn.name + "()");
      }
      currentInsn = currentInsn.getPrevious();
    }

    return params;
  }

  private void processVariableInstruction(StringBuilder sb, VarInsnNode varInsn, MethodNode method, String callerClass,
      Map<Integer, String> localVars) {
    String varName = getVariableName(varInsn.var, method);
    String varType = getDetailedType(getVariableType(varInsn.var, method));
    String fullVarInfo = varName + ":" + varType;
    localVars.put(varInsn.var, fullVarInfo);

    String callerName = getInterfaceName(callerClass);
    String operation = varInsn.getOpcode() == Opcodes.ILOAD || varInsn.getOpcode() == Opcodes.ALOAD ? "Load" : "Store";

    logger.debug("{} variable: {} in method: {}.{}", operation, fullVarInfo, callerClass, method.name);

    sb.append("note over ").append(callerName).append(" : ").append(operation).append(" ").append(fullVarInfo)
        .append("\n");
  }

  private String[] getParameterNames(String owner, String methodName, String methodDesc, List<ClassNode> allClasses) {
    ClassNode classNode = findClassByName(allClasses, owner);
    if (classNode != null) {
      for (MethodNode method : classNode.methods) {
        if (method.name.equals(methodName) && method.desc.equals(methodDesc)) {
          if (method.localVariables != null) {
            return method.localVariables.stream()
                .filter(lv -> lv.index > 0) // Skip 'this' parameter for instance methods
                .map(lv -> lv.name)
                .toArray(String[]::new);
          }
          break;
        }
      }
    }
    return null;
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
      List<ExternalCallInfo> externalCalls, Set<String> callStack) {

    String condition = getConditionFromPreviousInsn(jumpInsn.getPrevious());
    sb.append("alt ").append(condition).append("\n");

    // Process the 'if' block
    AbstractInsnNode currentInsn = jumpInsn.getNext();
    while (currentInsn != null && currentInsn != jumpInsn.label) {
      processInstruction(sb, currentInsn, method, allClasses, depth + 1, callerClass, localVars, externalCalls,
          callStack);
      currentInsn = currentInsn.getNext();
    }

    // Process the 'else' block if it exists
    sb.append("else\n");
    while (currentInsn != null
        && !(currentInsn instanceof LabelNode && ((LabelNode) currentInsn).getLabel() == jumpInsn.label.getLabel())) {
      processInstruction(sb, currentInsn, method, allClasses, depth + 1, callerClass, localVars, externalCalls,
          callStack);
      currentInsn = currentInsn.getNext();
    }

    sb.append("end\n");
  }

  private String getConditionFromPreviousInsn(AbstractInsnNode insn) {
    StringBuilder condition = new StringBuilder();
    AbstractInsnNode currentInsn = insn;

    // Traverse backwards to find the condition
    while (currentInsn != null && !(currentInsn instanceof JumpInsnNode)) {
      if (currentInsn instanceof VarInsnNode) {
        VarInsnNode varInsn = (VarInsnNode) currentInsn;
        condition.insert(0, "var" + varInsn.var + ".");
      } else if (currentInsn instanceof LdcInsnNode) {
        LdcInsnNode ldcInsn = (LdcInsnNode) currentInsn;
        condition.insert(0, ldcInsn.cst.toString() + " ");
      } else if (currentInsn instanceof MethodInsnNode) {
        MethodInsnNode methodInsn = (MethodInsnNode) currentInsn;
        condition.insert(0, methodInsn.name + "() ");
      } else if (currentInsn instanceof FieldInsnNode) {
        FieldInsnNode fieldInsn = (FieldInsnNode) currentInsn;
        condition.insert(0, fieldInsn.name + " ");
      }

      currentInsn = currentInsn.getPrevious();
    }

    if (currentInsn instanceof JumpInsnNode) {
      JumpInsnNode jumpInsn = (JumpInsnNode) currentInsn;
      String operator = getOperatorFromJumpInsn(jumpInsn);
      condition.append(operator).append(" ");

      // Add the right-hand side of the condition
      AbstractInsnNode nextInsn = jumpInsn.getNext();
      if (nextInsn instanceof LdcInsnNode) {
        LdcInsnNode ldcInsn = (LdcInsnNode) nextInsn;
        condition.append(ldcInsn.cst.toString());
      } else if (nextInsn instanceof VarInsnNode) {
        VarInsnNode varInsn = (VarInsnNode) nextInsn;
        condition.append("var").append(varInsn.var);
      }
    }

    return condition.length() > 0 ? condition.toString().trim().replace(".", " ") : "[complex condition]";
  }

  private String getOperatorFromJumpInsn(JumpInsnNode jumpInsn) {
    switch (jumpInsn.getOpcode()) {
      case Opcodes.IFEQ:
        return "==";
      case Opcodes.IFNE:
        return "!=";
      case Opcodes.IFLT:
        return "<";
      case Opcodes.IFGE:
        return ">=";
      case Opcodes.IFGT:
        return ">";
      case Opcodes.IFLE:
        return "<=";
      case Opcodes.IF_ICMPEQ:
        return "==";
      case Opcodes.IF_ICMPNE:
        return "!=";
      case Opcodes.IF_ICMPLT:
        return "<";
      case Opcodes.IF_ICMPGE:
        return ">=";
      case Opcodes.IF_ICMPGT:
        return ">";
      case Opcodes.IF_ICMPLE:
        return "<=";
      case Opcodes.IFNULL:
        return "== null";
      case Opcodes.IFNONNULL:
        return "!= null";
      default:
        return "?";
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
      ClassNode cachedResult = implementationCache.get(interfaceName);
      if (cachedResult != null) {
        logger.debug("Found cached implementation for {}: {}", interfaceName, cachedResult.name);
      } else {
        logger.debug("Cached null implementation for {}", interfaceName);
      }
      return cachedResult;
    }

    ClassNode result = findImplementationClassInternal(allClasses, interfaceName);

    // Cache the result (even if it's null)
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

    // Step 6: Apply custom rules
    for (ImplementationMatcher matcher : customRules) {
      Optional<ClassNode> match = allClasses.stream()
          .filter(classNode -> matcher.matches(classNode, interfaceName))
          .findFirst();
      if (match.isPresent()) {
        logger.info("Found implementation for {} using custom rule: {}", interfaceName, match.get().name);
        return match.get();
      }
    }

    // Step 7: Look for classes with similar names (if naming conventions are
    // enabled)
    if (useNamingConventions) {
      String simpleInterfaceName = getSimpleClassName(interfaceName);
      List<ClassNode> potentialMatches = allClasses.stream()
          .filter(classNode -> getSimpleClassName(classNode.name).contains(simpleInterfaceName))
          .collect(Collectors.toList());

      if (!potentialMatches.isEmpty()) {
        logger.warn("No exact implementation found for {}. Using potential match based on naming convention: {}",
            interfaceName, potentialMatches.get(0).name);
        return potentialMatches.get(0);
      }
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
    String callerName = getInterfaceName(callerClass);
    String constantValue = ldcInsn.cst.toString();

    sb.append("note over ").append(callerName).append(" : Constant: ").append(constantValue).append("\n");
  }

  private void processLambdaOrMethodReference(StringBuilder sb, InvokeDynamicInsnNode insnNode,
      List<ClassNode> allClasses,
      int depth, String callerClass, List<ExternalCallInfo> externalCalls, Set<String> callStack) {
    String callerName = getInterfaceName(callerClass);
    String lambdaName = insnNode.name;
    String lambdaDesc = insnNode.desc;

    sb.append("note over ").append(callerName).append("\n");
    sb.append("Lambda or Method Reference: ").append(lambdaName).append("\n");
    sb.append("Descriptor: ").append(lambdaDesc).append("\n");
    sb.append("end note\n");

    // Try to find the implemented method
    String implementedMethodName = extractImplementedMethodName(insnNode);
    if (implementedMethodName != null) {
      ClassNode targetClass = findClassByName(allClasses, callerClass);
      if (targetClass != null) {
        MethodNode targetMethod = findMethodByName(targetClass, implementedMethodName);
        if (targetMethod != null) {
          processMethod(sb, targetMethod, allClasses, depth + 1, callerClass, new HashMap<>(), externalCalls,
              callStack);
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
      List<ExternalCallInfo> externalCalls, Set<String> callStack) {
    sb.append("group #LightGray Try\n");

    AbstractInsnNode currentInsn = method.instructions.get(method.instructions.indexOf(tryCatchBlock.start));
    while (currentInsn != tryCatchBlock.end) {
      if (currentInsn instanceof MethodInsnNode) {
        processMethodCall(sb, (MethodInsnNode) currentInsn, allClasses, depth + 1, callerClass, localVars,
            externalCalls, callStack);
      }
      currentInsn = currentInsn.getNext();
    }

    sb.append("end\n");
    sb.append("group #LightCoral Catch ").append(getSimpleClassName(tryCatchBlock.type)).append("\n");

    currentInsn = method.instructions.get(method.instructions.indexOf(tryCatchBlock.handler));
    while (!(currentInsn instanceof LabelNode)) {
      if (currentInsn instanceof MethodInsnNode) {
        processMethodCall(sb, (MethodInsnNode) currentInsn, allClasses, depth + 1, callerClass, localVars,
            externalCalls, callStack);
      }
      currentInsn = currentInsn.getNext();
    }

    sb.append("end\n");
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
}