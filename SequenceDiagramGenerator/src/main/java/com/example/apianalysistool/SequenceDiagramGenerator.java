package com.example.apianalysistool;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

public class SequenceDiagramGenerator {
    private final Set<String> processedMethods = new HashSet<>();
    private final List<String> orderedParticipants = new ArrayList<>();
    private final Map<String, String> implToInterfaceMap = new HashMap<>();
    private final Map<String, String> classToHostMap = new HashMap<>();
    private final Map<String, Set<String>> methodToAnnotations = new HashMap<>();
    private int groupCounter = 0;
    private String currentClass;

    public void generateSequenceDiagram(List<ClassNode> allClasses, List<APIInfo> apis,
            List<ExternalCallInfo> externalCalls) {
        StringBuilder sb = new StringBuilder();
        sb.append("@startuml\n");
        sb.append("skinparam sequenceArrowThickness 2\n");
        sb.append("skinparam roundcorner 20\n");
        sb.append("skinparam maxmessagesize 60\n");
        sb.append("skinparam responseMessageBelowArrow true\n");
        sb.append("skinparam ParticipantPadding 20\n");
        sb.append("skinparam BoxPadding 10\n\n");

        mapImplementationsToInterfaces(allClasses);
        findWebClientHostNames(allClasses);
        mapMethodAnnotations(allClasses);
        appendParticipants(sb, allClasses, externalCalls);

        for (APIInfo api : apis) {
            generateSequenceForAPI(sb, api, allClasses);
        }

        sb.append("@enduml");

        try (FileWriter writer = new FileWriter("sequence_diagram.puml")) {
            writer.write(sb.toString());
        } catch (IOException e) {
            System.out.println("Error generating sequence diagram: " + e.getMessage());
        }
    }

    private void generateSequenceForAPI(StringBuilder sb, APIInfo api, List<ClassNode> allClasses) {
        sb.append("== ").append(api.getMethodName()).append(" ==\n");
        String controllerName = getSimpleClassName(getClassName(api.getMethodName()));
        sb.append("Client -> ").append(controllerName).append(" : ")
            .append(api.getHttpMethod()).append(" ").append(api.getPath()).append("\n");
        sb.append("activate ").append(controllerName).append("\n");

        ClassNode controllerClass = findClassByName(allClasses, controllerName);
        if (controllerClass != null) {
            MethodNode method = findMethodByName(controllerClass, getMethodName(api.getMethodName()));
            if (method != null) {
                processedMethods.clear();
                processMethod(sb, method, allClasses, 1, controllerClass.name, new HashMap<>());
            }
        }

        sb.append(controllerName).append(" --> Client : HTTP Response (")
            .append(api.getReturnType()).append(")\n");
        sb.append("deactivate ").append(controllerName).append("\n\n");
    }

    private void processMethod(StringBuilder sb, MethodNode method, List<ClassNode> allClasses, int depth, String callerClass, Map<Integer, String> localVars) {
        if (depth > 10 || processedMethods.contains(callerClass + "." + method.name)) return;
        processedMethods.add(callerClass + "." + method.name);

        Set<String> annotations = methodToAnnotations.get(callerClass + "." + method.name);
        if (annotations != null && !annotations.isEmpty()) {
            sb.append("note over ").append(getInterfaceName(callerClass)).append(" : ")
              .append(String.join(", ", annotations)).append("\n");
        }

        boolean isTransactional = annotations != null && annotations.contains("@Transactional");
        boolean isAsync = annotations != null && annotations.contains("@Async");

        if (isTransactional) {
            sb.append("group Transaction\n");
        }
        if (isAsync) {
            sb.append("group Asynchronous Operation\n");
        }

        try {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode) {
                    processMethodCall(sb, (MethodInsnNode) insn, allClasses, depth, callerClass, localVars);
                } else if (insn instanceof InvokeDynamicInsnNode) {
                    processLambdaOrMethodReference(sb, (InvokeDynamicInsnNode) insn, allClasses, depth, callerClass);
                } else if (insn instanceof JumpInsnNode) {
                    processConditionalFlow(sb, (JumpInsnNode) insn, method, allClasses, depth, callerClass, localVars);
                } else if (insn instanceof VarInsnNode) {
                    processVariableInstruction(sb, (VarInsnNode) insn, localVars);
                } else if (insn instanceof LdcInsnNode) {
                    processConstantInstruction(sb, (LdcInsnNode) insn, localVars);
                } else if (insn instanceof LabelNode) {
                    processLabelNode(sb, (LabelNode) insn, method);
                }
            }

            // Process try-catch blocks
            for (TryCatchBlockNode tryCatchBlock : method.tryCatchBlocks) {
                processTryCatchBlock(sb, tryCatchBlock, method, allClasses, depth, callerClass, localVars);
            }
        } catch (Exception e) {
            sb.append("note over ").append(getInterfaceName(callerClass)).append(" : Exception: ")
              .append(e.getClass().getSimpleName()).append(" - ").append(e.getMessage()).append("\n");
        }

        if (isAsync) {
            sb.append("end\n");
        }
        if (isTransactional) {
            sb.append("end\n");
        }
    }

    private void processMethodCall(StringBuilder sb, MethodInsnNode methodInsn, List<ClassNode> allClasses, int depth, String callerClass, Map<Integer, String> localVars) {
        String className = getSimpleClassName(methodInsn.owner);
        String methodName = methodInsn.name;

        if (className.endsWith("Service") || className.endsWith("Repository") || className.contains("Client")) {
            String callerName = getInterfaceName(callerClass);
            sb.append(callerName).append(" -> ").append(className).append(" : ").append(methodName).append("(");
            
            // Add method parameters
            Type[] argumentTypes = Type.getArgumentTypes(methodInsn.desc);
            for (int i = 0; i < argumentTypes.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(localVars.getOrDefault(i, getSimplifiedTypeName(argumentTypes[i].getClassName())));
            }
            sb.append(")\n");
            
            sb.append("activate ").append(className).append("\n");

            ClassNode targetClass = findImplementationClass(allClasses, methodInsn.owner);
            if (targetClass != null) {
                MethodNode targetMethod = findMethodByName(targetClass, methodName);
                if (targetMethod != null) {
                    processMethod(sb, targetMethod, allClasses, depth + 1, targetClass.name, new HashMap<>());
                }
            }

            if (className.endsWith("Repository")) {
                processDatabaseInteraction(sb, className);
            } else if (className.contains("Client")) {
                processExternalApiCall(sb, targetClass, methodName);
            }

            String returnType = Type.getReturnType(methodInsn.desc).getClassName();
            processReturnValue(sb, className, callerName, returnType);
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

    private void processReturnValue(StringBuilder sb, String className, String callerName, String returnType) {
        if (returnType.contains("Mono") || returnType.contains("Flux")) {
            sb.append("note right of ").append(className).append("\n")
              .append("Asynchronous operation\n")
              .append("Return type: ").append(getSimplifiedTypeName(returnType)).append("\n")
              .append("end note\n");
            sb.append(className).append(" --> ").append(callerName).append(" : return ").append(getSimplifiedTypeName(returnType)).append("\n");
        } else {
            sb.append(className).append(" --> ").append(callerName).append(" : return ").append(getSimplifiedTypeName(returnType)).append("\n");
        }
        sb.append("deactivate ").append(className).append("\n");
    }

    private String getSimplifiedTypeName(String fullTypeName) {
        if (fullTypeName.contains("<")) {
            String baseType = fullTypeName.substring(0, fullTypeName.indexOf('<'));
            String paramType = fullTypeName.substring(fullTypeName.indexOf('<') + 1, fullTypeName.lastIndexOf('>'));
            return getSimpleClassName(baseType) + "<" + getSimpleClassName(paramType) + ">";
        }
        return getSimpleClassName(fullTypeName);
    }

    private void processConditionalFlow(StringBuilder sb, JumpInsnNode jumpInsn, MethodNode method, List<ClassNode> allClasses, int depth, String callerClass, Map<Integer, String> localVars) {
        String callerName = getInterfaceName(callerClass);
        sb.append("alt ").append(getConditionDescription(jumpInsn)).append("\n");
        
        groupCounter++;
        String groupName = "group_" + groupCounter;
        sb.append("group #LightYellow ").append(groupName).append("\n");
        
        // Process the "if" block
        AbstractInsnNode currentInsn = jumpInsn.getNext();
        while (currentInsn != null && currentInsn != jumpInsn.label) {
            if (currentInsn instanceof MethodInsnNode) {
                processMethodCall(sb, (MethodInsnNode) currentInsn, allClasses, depth + 1, callerClass, localVars);
            }
            currentInsn = currentInsn.getNext();
        }
        
        sb.append("end\n");
        sb.append("else\n");
        
        groupCounter++;
        String elseGroupName = "group_" + groupCounter;
        sb.append("group #LightCyan ").append(elseGroupName).append("\n");
        
        // Process the "else" block
        while (currentInsn != null && !(currentInsn instanceof LabelNode && ((LabelNode) currentInsn).getLabel() == jumpInsn.label.getLabel())) {
            if (currentInsn instanceof MethodInsnNode) {
                processMethodCall(sb, (MethodInsnNode) currentInsn, allClasses, depth + 1, callerClass, localVars);
            }
            currentInsn = currentInsn.getNext();
        }
        
        sb.append("end\n");
        sb.append("end\n");
    }

    private String getConditionDescription(JumpInsnNode jumpInsn) {
        switch (jumpInsn.getOpcode()) {
            case Opcodes.IFEQ: return "if equals";
            case Opcodes.IFNE: return "if not equals";
            case Opcodes.IFLT: return "if less than";
            case Opcodes.IFGE: return "if greater than or equals";
            case Opcodes.IFGT: return "if greater than";
            case Opcodes.IFLE: return "if less than or equals";
            default: return "condition";
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

            // Add WebClient host names as participants
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
            return parsedUrl.getHost();
        } catch (java.net.MalformedURLException e) {
            return "ExternalService";
        }
    }

    private String getDatabaseName(String repositoryName) {
        return repositoryName.replace("Repository", "DB");
    }

    private String extractBaseUrl(ClassNode classNode) {
        // Search for WebClient or RestTemplate initialization with baseUrl
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
        for (ClassNode classNode : allClasses) {
            if (classNode.interfaces.contains(interfaceName)) {
                return classNode;
            }
        }
        return findClassByName(allClasses, interfaceName);
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

    private void processVariableInstruction(StringBuilder sb, VarInsnNode varInsn, Map<Integer, String> localVars) {
        String varName = "var" + varInsn.var;
        if (varInsn.getOpcode() == Opcodes.ASTORE) {
            localVars.put(varInsn.var, varName);
            sb.append("note over ").append(getInterfaceName(currentClass)).append(" : Store ").append(varName).append("\n");
        } else if (varInsn.getOpcode() == Opcodes.ALOAD) {
            sb.append("note over ").append(getInterfaceName(currentClass)).append(" : Load ").append(varName).append("\n");
        }
    }

    private void processConstantInstruction(StringBuilder sb, LdcInsnNode ldcInsn, Map<Integer, String> localVars) {
        String constantValue = ldcInsn.cst.toString();
        sb.append("note over ").append(getInterfaceName(currentClass)).append(" : Load constant: ").append(constantValue).append("\n");
    }

    private void processLambdaOrMethodReference(StringBuilder sb, InvokeDynamicInsnNode insn, List<ClassNode> allClasses, int depth, String callerClass) {
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
                    processMethod(sb, targetMethod, allClasses, depth + 1, callerClass, new HashMap<>());
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

    private void processTryCatchBlock(StringBuilder sb, TryCatchBlockNode tryCatchBlock, MethodNode method, List<ClassNode> allClasses, int depth, String callerClass, Map<Integer, String> localVars) {
        sb.append("group #LightGray Try\n");
        
        AbstractInsnNode currentInsn = method.instructions.get(method.instructions.indexOf(tryCatchBlock.start));
        while (currentInsn != tryCatchBlock.end) {
            if (currentInsn instanceof MethodInsnNode) {
                processMethodCall(sb, (MethodInsnNode) currentInsn, allClasses, depth + 1, callerClass, localVars);
            }
            currentInsn = currentInsn.getNext();
        }
        
        sb.append("end\n");
        sb.append("group #LightCoral Catch ").append(getSimpleClassName(tryCatchBlock.type)).append("\n");
        
        currentInsn = method.instructions.get(method.instructions.indexOf(tryCatchBlock.handler));
        while (!(currentInsn instanceof LabelNode)) {
            if (currentInsn instanceof MethodInsnNode) {
                processMethodCall(sb, (MethodInsnNode) currentInsn, allClasses, depth + 1, callerClass, localVars);
            }
            currentInsn = currentInsn.getNext();
        }
        
        sb.append("end\n");
    }
}