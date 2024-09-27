package com.example.apianalysistool;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import net.sourceforge.plantuml.SourceStringReader;

import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicInteger;

public class UMLGenerator {
    private static final Set<String> SERVICE_ANNOTATIONS = new HashSet<>(Arrays.asList(
            "Lorg/springframework/stereotype/Service;",
            "Lorg/springframework/web/bind/annotation/RestController;"
    ));

    private static final Set<String> REPOSITORY_MARKERS = new HashSet<>(Arrays.asList(
            "org/springframework/data/repository/Repository",
            "org/springframework/data/jpa/repository/JpaRepository"
    ));

    private static final Set<String> WEBCLIENT_MARKERS = new HashSet<>(Arrays.asList(
            "org/springframework/web/reactive/function/client/WebClient",
            "org/springframework/web/client/RestTemplate"
    ));

    private static final Pattern WEB_CLIENT_URL_PATTERN = Pattern.compile("baseUrl\\(\"(http[s]?://[^\"]+)\"\\)");

    public void generateDiagrams(List<APIInfo> apis, List<ExternalCallInfo> externalCalls, List<ClassNode> allClasses) {
        generateSequenceDiagram(apis, externalCalls, allClasses);
        generateClassDiagram(apis, allClasses);
    }

    private void generateSequenceDiagram(List<APIInfo> apis, List<ExternalCallInfo> externalCalls, List<ClassNode> allClasses) {
        String plantUML = generatePlantUMLSequenceDiagram(apis, externalCalls, allClasses);
        String mermaid = generateMermaidSequenceDiagram(apis, externalCalls, allClasses);

        generateDiagramFile(plantUML, "detailed_sequence_diagram.puml");
        generatePlantUMLImage(plantUML, "detailed_sequence_diagram.png");
        generateTextFile(mermaid, "detailed_sequence_diagram.mmd");
    }

    private void generateClassDiagram(List<APIInfo> apis, List<ClassNode> allClasses) {
        String plantUML = generatePlantUMLClassDiagram(apis, allClasses);
        String mermaid = generateMermaidClassDiagram(apis, allClasses);

        generateDiagramFile(plantUML, "detailed_class_diagram.puml");
        generatePlantUMLImage(plantUML, "detailed_class_diagram.png");
        generateTextFile(mermaid, "detailed_class_diagram.mmd");
    }

    private String generatePlantUMLSequenceDiagram(List<APIInfo> apis, List<ExternalCallInfo> externalCalls, List<ClassNode> allClasses) {
        StringBuilder uml = new StringBuilder("@startuml\n");
        uml.append("actor Client\n");

        Map<String, String> participants = new HashMap<>();
        Map<String, String> webClientUrls = new HashMap<>();
        Map<String, ClassNode> implementationMap = new HashMap<>();

        // Define participants and build implementation map
        for (ClassNode classNode : allClasses) {
            if (isService(classNode) || isRepository(classNode) || isWebClient(classNode)) {
                String className = classNode.name.replace('/', '.');
                String interfaceName = findInterfaceForClass(classNode, allClasses);
                if (interfaceName != null) {
                    addParticipant(participants, interfaceName, getParticipantType(classNode));
                    implementationMap.put(interfaceName, classNode);
                } else {
                    addParticipant(participants, className, getParticipantType(classNode));
                    implementationMap.put(className, classNode);
                }
            }
            String webClientUrl = findWebClientBaseUrl(classNode);
            if (webClientUrl != null) {
                webClientUrls.put(classNode.name.replace('/', '.'), webClientUrl);
            }
        }

        // Add participants to the diagram
        for (Map.Entry<String, String> entry : participants.entrySet()) {
            uml.append("participant \"").append(entry.getKey()).append("\" as ").append(entry.getValue()).append("\n");
        }

        // Add WebClient participants
        for (Map.Entry<String, String> entry : webClientUrls.entrySet()) {
            uml.append("participant \"").append(entry.getValue()).append("\" as ").append(entry.getValue().replaceAll("[^a-zA-Z0-9]", "_")).append("\n");
        }

        // Generate sequence for exposed APIs
        for (APIInfo api : apis) {
            String controllerName = participants.get(getClassName(api.getMethodName()));
            uml.append("Client -> ").append(controllerName).append(" : ").append(api.getHttpMethod()).append(" ").append(api.getPath()).append("\n");
            uml.append("activate ").append(controllerName).append("\n");

            ClassNode classNode = implementationMap.get(getClassName(api.getMethodName()));
            if (classNode != null) {
                generateInternalCalls(uml, classNode, api.getMethodName(), participants, webClientUrls, externalCalls, allClasses, implementationMap);
            }

            uml.append(controllerName).append(" --> Client : response\n");
            uml.append("deactivate ").append(controllerName).append("\n");
        }

        // Generate sequence for external calls
        for (ExternalCallInfo externalCall : externalCalls) {
            String callerName = participants.get(getClassName(externalCall.getPurpose()));
            String externalServiceName = getExternalServiceName(externalCall.getUrl());
            uml.append(callerName).append(" -> ").append(externalServiceName).append(" : ").append(externalCall.getHttpMethod()).append(" ").append(externalCall.getUrl()).append("\n");
            uml.append("activate ").append(externalServiceName).append("\n");
            uml.append(externalServiceName).append(" --> ").append(callerName).append(" : response\n");
            uml.append("deactivate ").append(externalServiceName).append("\n");
        }

        uml.append("@enduml");
        return uml.toString();
    }

    private String generateMermaidSequenceDiagram(List<APIInfo> apis, List<ExternalCallInfo> externalCalls, List<ClassNode> allClasses) {
        StringBuilder mmd = new StringBuilder("sequenceDiagram\n");
        mmd.append("    actor Client\n");

        Map<String, String> participants = new HashMap<>();
        Map<String, String> webClientUrls = new HashMap<>();
        Map<String, ClassNode> implementationMap = new HashMap<>();

        // Define participants and build implementation map
        for (ClassNode classNode : allClasses) {
            if (isService(classNode)) {
                String className = classNode.name.replace('/', '.');
                String interfaceName = findInterfaceForClass(classNode, allClasses);
                if (interfaceName != null) {
                    addParticipant(participants, interfaceName, getParticipantType(classNode));
                    implementationMap.put(interfaceName, classNode);
                } else {
                    addParticipant(participants, className, getParticipantType(classNode));
                    implementationMap.put(className, classNode);
                }
            }
            String webClientUrl = findWebClientBaseUrl(classNode);
            if (webClientUrl != null) {
                webClientUrls.put(classNode.name.replace('/', '.'), webClientUrl);
            }
        }

        // Add participants to the diagram
        for (Map.Entry<String, String> entry : participants.entrySet()) {
            mmd.append("    participant ").append(entry.getValue()).append(" as ").append(entry.getKey()).append("\n");
        }

        // Add WebClient participants
        for (Map.Entry<String, String> entry : webClientUrls.entrySet()) {
            mmd.append("    participant ").append(entry.getValue().replaceAll("[^a-zA-Z0-9]", "_")).append(" as ").append(entry.getValue()).append("\n");
        }

        // Generate sequence
        for (APIInfo api : apis) {
            String controllerName = participants.get(getClassName(api.getMethodName()));

            mmd.append("    Client->>").append(controllerName).append(": ").append(api.getHttpMethod()).append(" ").append(api.getPath()).append("\n");
            mmd.append("    activate ").append(controllerName).append("\n");

            ClassNode classNode = implementationMap.get(getClassName(api.getMethodName()));
            if (classNode != null) {
                generateInternalCallsMermaid(mmd, classNode, api.getMethodName(), participants, webClientUrls, externalCalls, allClasses, implementationMap);
            }

            mmd.append("    ").append(controllerName).append("->>Client: response\n");
            mmd.append("    deactivate ").append(controllerName).append("\n");
        }

        return mmd.toString();
    }

    private void addParticipant(Map<String, String> participants, String className, String type) {
        if (!participants.containsKey(className)) {
            String participantName = type + participants.size();
            participants.put(className, participantName);
        }
    }

    private void addInternalParticipants(Map<String, String> participants, ClassNode classNode) {
        for (FieldNode field : classNode.fields) {
            Type fieldType = Type.getType(field.desc);
            String fieldClassName = fieldType.getClassName();
            if (isRepository(field)) {
                addParticipant(participants, fieldClassName, "Repository");
            } else if (isWebClient(field)) {
                addParticipant(participants, fieldClassName, "WebClient");
            } else {
                addParticipant(participants, fieldClassName, "Service");
            }
        }
    }

    private void generateInternalCalls(StringBuilder uml, ClassNode classNode, String methodName, Map<String, String> participants,
                                       Map<String, String> webClientUrls, List<ExternalCallInfo> externalCalls,
                                       List<ClassNode> allClasses, Map<String, ClassNode> implementationMap) {
        for (MethodNode method : classNode.methods) {
            if ((method.name + method.desc).equals(getMethodName(methodName))) {
                for (AbstractInsnNode insn : method.instructions) {
                    if (insn instanceof MethodInsnNode) {
                        MethodInsnNode methodInsn = (MethodInsnNode) insn;
                        String calledClass = methodInsn.owner.replace('/', '.');
                        if (participants.containsKey(calledClass)) {
                            String callerName = participants.get(findInterfaceForClass(classNode, allClasses));
                            String calleeName = participants.get(calledClass);
                            uml.append(callerName).append(" -> ").append(calleeName).append(" : ").append(methodInsn.name).append("\n");
                            uml.append("activate ").append(calleeName).append("\n");

                            // Recursively generate internal calls for the called method
                            ClassNode calledClassNode = implementationMap.get(calledClass);
                            if (calledClassNode != null) {
                                generateInternalCalls(uml, calledClassNode, calledClass + "." + methodInsn.name, participants, webClientUrls, externalCalls, allClasses, implementationMap);
                            }

                            uml.append(calleeName).append(" --> ").append(callerName).append(" : return\n");
                            uml.append("deactivate ").append(calleeName).append("\n");
                        } else if (webClientUrls.containsKey(calledClass)) {
                            String callerName = participants.get(findInterfaceForClass(classNode, allClasses));
                            String webClientUrl = webClientUrls.get(calledClass);
                            String webClientName = webClientUrl.replaceAll("[^a-zA-Z0-9]", "_");
                            uml.append(callerName).append(" -> ").append(webClientName).append(" : ").append(methodInsn.name).append("\n");
                            uml.append("activate ").append(webClientName).append("\n");
                            uml.append(webClientName).append(" --> ").append(callerName).append(" : return\n");
                            uml.append("deactivate ").append(webClientName).append("\n");
                        }
                    }
                }
            }
        }
    }

    private void generateInternalCallsMermaid(StringBuilder mmd, ClassNode classNode, String methodName, Map<String, String> participants,
                                              Map<String, String> webClientUrls, List<ExternalCallInfo> externalCalls,
                                              List<ClassNode> allClasses, Map<String, ClassNode> implementationMap) {
        for (MethodNode method : classNode.methods) {
            if ((method.name + method.desc).equals(getMethodName(methodName))) {
                for (AbstractInsnNode insn : method.instructions) {
                    if (insn instanceof MethodInsnNode) {
                        MethodInsnNode methodInsn = (MethodInsnNode) insn;
                        String calledClass = methodInsn.owner.replace('/', '.');
                        if (participants.containsKey(calledClass)) {
                            String callerName = participants.get(findInterfaceForClass(classNode, allClasses));
                            String calleeName = participants.get(calledClass);
                            mmd.append("    ").append(callerName).append("->>").append(calleeName).append(": ").append(methodInsn.name).append("\n");
                            mmd.append("    activate ").append(calleeName).append("\n");

                            // Recursively generate internal calls for the called method
                            ClassNode calledClassNode = implementationMap.get(calledClass);
                            if (calledClassNode != null) {
                                generateInternalCallsMermaid(mmd, calledClassNode, calledClass + "." + methodInsn.name, participants, webClientUrls, externalCalls, allClasses, implementationMap);
                            }

                            mmd.append("    ").append(calleeName).append("->>").append(callerName).append(": return\n");
                            mmd.append("    deactivate ").append(calleeName).append("\n");
                        } else if (webClientUrls.containsKey(calledClass)) {
                            String callerName = participants.get(findInterfaceForClass(classNode, allClasses));
                            String webClientUrl = webClientUrls.get(calledClass);
                            String webClientName = webClientUrl.replaceAll("[^a-zA-Z0-9]", "_");
                            mmd.append("    ").append(callerName).append("->>").append(webClientName).append(": ").append(methodInsn.name).append("\n");
                            mmd.append("    activate ").append(webClientName).append("\n");
                            mmd.append("    ").append(webClientName).append("->>").append(callerName).append(": return\n");
                            mmd.append("    deactivate ").append(webClientName).append("\n");
                        }
                    }
                }
            }
        }
    }

    private String findInterfaceForClass(ClassNode classNode, List<ClassNode> allClasses) {
        if (classNode.interfaces != null) {
            for (String interfaceName : classNode.interfaces) {
                String fullInterfaceName = interfaceName.replace('/', '.');
                if (isService(findClassNode(allClasses, fullInterfaceName))) {
                    return fullInterfaceName;
                }
            }
        }
        return classNode.name.replace('/', '.');
    }

    private boolean isService(ClassNode classNode) {
        if (classNode == null) return false;
        if (classNode.visibleAnnotations != null) {
            for (AnnotationNode annotation : classNode.visibleAnnotations) {
                if (SERVICE_ANNOTATIONS.contains(annotation.desc)) {
                    return true;
                }
            }
        }
        return false;
    }

    private String getParticipantType(ClassNode classNode) {
        if (classNode.visibleAnnotations != null) {
            for (AnnotationNode annotation : classNode.visibleAnnotations) {
                if (annotation.desc.equals("Lorg/springframework/web/bind/annotation/RestController;")) {
                    return "Controller";
                } else if (annotation.desc.equals("Lorg/springframework/stereotype/Service;")) {
                    return "Service";
                }
            }
        }
        return "Component";
    }

    private String findWebClientBaseUrl(ClassNode classNode) {
        for (MethodNode method : classNode.methods) {
            if (method.name.equals("<init>")) {
                for (AbstractInsnNode insn : method.instructions) {
                    if (insn instanceof LdcInsnNode) {
                        LdcInsnNode ldcInsn = (LdcInsnNode) insn;
                        if (ldcInsn.cst instanceof String) {
                            String constant = (String) ldcInsn.cst;
                            Matcher matcher = WEB_CLIENT_URL_PATTERN.matcher(constant);
                            if (matcher.find()) {
                                return matcher.group(1);
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    private String generatePlantUMLClassDiagram(List<APIInfo> apis, List<ClassNode> allClasses) {
        StringBuilder uml = new StringBuilder("@startuml\n");

        Map<String, ClassNode> relevantClasses = new HashMap<>();

        for (APIInfo api : apis) {
            String className = getClassName(api.getMethodName());
            ClassNode classNode = findClassNode(allClasses, className);
            if (classNode != null) {
                relevantClasses.put(className, classNode);
                addRelatedClasses(classNode, allClasses, relevantClasses);
            }
        }

        for (ClassNode classNode : relevantClasses.values()) {
            generateClassDiagramForClass(uml, classNode, allClasses);
        }

        uml.append("@enduml");
        return uml.toString();
    }
    private void addRelatedClasses(ClassNode classNode, List<ClassNode> allClasses, Map<String, ClassNode> relevantClasses) {
        if (classNode.interfaces != null) {
            for (String interfaceName : classNode.interfaces) {
                String fullInterfaceName = interfaceName.replace('/', '.');
                ClassNode interfaceNode = findClassNode(allClasses, fullInterfaceName);
                if (interfaceNode != null && !relevantClasses.containsKey(fullInterfaceName)) {
                    relevantClasses.put(fullInterfaceName, interfaceNode);
                }
            }
        }

        for (FieldNode field : classNode.fields) {
            Type fieldType = Type.getType(field.desc);
            String fieldClassName = fieldType.getClassName();
            ClassNode fieldClassNode = findClassNode(allClasses, fieldClassName);
            if (fieldClassNode != null && !relevantClasses.containsKey(fieldClassName)) {
                relevantClasses.put(fieldClassName, fieldClassNode);
                addRelatedClasses(fieldClassNode, allClasses, relevantClasses);
            }
        }
    }
    private String generateMermaidClassDiagram(List<APIInfo> apis, List<ClassNode> allClasses) {
        StringBuilder mmd = new StringBuilder("classDiagram\n");

        Map<String, ClassNode> relevantClasses = new HashMap<>();

        for (APIInfo api : apis) {
            String className = getClassName(api.getMethodName());
            ClassNode classNode = findClassNode(allClasses, className);
            if (classNode != null) {
                relevantClasses.put(className, classNode);
                addRelatedClasses(classNode, allClasses, relevantClasses);
            }
        }

        for (ClassNode classNode : relevantClasses.values()) {
            generateClassDiagramForClassMermaid(mmd, classNode, allClasses);
        }

        return mmd.toString();
    }

    private void generateClassDiagramForClass(StringBuilder uml, ClassNode classNode, List<ClassNode> allClasses) {
        String className = classNode.name.replace('/', '.');

        if ((classNode.access & org.objectweb.asm.Opcodes.ACC_INTERFACE) != 0) {
            uml.append("interface ");
        } else {
            uml.append("class ");
        }

        uml.append(className).append(" {\n");

        for (MethodNode method : classNode.methods) {
            if (!method.name.equals("<init>") && !method.name.equals("<clinit>")) {
                uml.append("  +").append(method.name).append("(");
                Type[] argTypes = Type.getArgumentTypes(method.desc);
                for (int i = 0; i < argTypes.length; i++) {
                    if (i > 0) uml.append(", ");
                    uml.append(argTypes[i].getClassName());
                }
                uml.append(") : ").append(Type.getReturnType(method.desc).getClassName()).append("\n");
            }
        }
        uml.append("}\n\n");

        if (classNode.interfaces != null) {
            for (String interfaceName : classNode.interfaces) {
                String fullInterfaceName = interfaceName.replace('/', '.');
                uml.append(fullInterfaceName).append(" <|.. ").append(className).append("\n");
            }
        }

        for (FieldNode field : classNode.fields) {
            Type fieldType = Type.getType(field.desc);
            String fieldClassName = fieldType.getClassName();
            uml.append(className).append(" --> ").append(fieldClassName).append("\n");
        }
    }

    private void generateClassDiagramForClassMermaid(StringBuilder mmd, ClassNode classNode, List<ClassNode> allClasses) {
        String className = classNode.name.replace('/', '.');

        mmd.append("    class ").append(className).append(" {\n");

        for (MethodNode method : classNode.methods) {
            if (!method.name.equals("<init>") && !method.name.equals("<clinit>")) {
                mmd.append("        +").append(method.name).append("(");
                Type[] argTypes = Type.getArgumentTypes(method.desc);
                for (int i = 0; i < argTypes.length; i++) {
                    if (i > 0) mmd.append(", ");
                    mmd.append(argTypes[i].getClassName());
                }
                mmd.append(") ").append(Type.getReturnType(method.desc).getClassName()).append("\n");
            }
        }
        mmd.append("    }\n");

        if (classNode.interfaces != null) {
            for (String interfaceName : classNode.interfaces) {
                String fullInterfaceName = interfaceName.replace('/', '.');
                mmd.append("    ").append(fullInterfaceName).append(" <|-- ").append(className).append("\n");
            }
        }

        for (FieldNode field : classNode.fields) {
            Type fieldType = Type.getType(field.desc);
            String fieldClassName = fieldType.getClassName();
            mmd.append("    ").append(className).append(" --> ").append(fieldClassName).append("\n");
        }
    }

    private ClassNode findClassNode(List<ClassNode> allClasses, String className) {
        for (ClassNode classNode : allClasses) {
            if (classNode.name.replace('/', '.').equals(className)) {
                return classNode;
            }
        }
        return null;
    }

    private boolean isRepository(FieldNode field) {
        return REPOSITORY_MARKERS.stream().anyMatch(marker -> field.desc.contains(marker));
    }

    private boolean isWebClient(FieldNode field) {
        return WEBCLIENT_MARKERS.stream().anyMatch(marker -> field.desc.contains(marker));
    }

    private String getClassName(String fullMethodName) {
        return fullMethodName.substring(0, fullMethodName.lastIndexOf('.'));
    }

    private String getMethodName(String fullMethodName) {
        return fullMethodName.substring(fullMethodName.lastIndexOf('.') + 1);
    }

    private void generateDiagramFile(String content, String fileName) {
        try (FileWriter writer = new FileWriter(fileName)) {
            writer.write(content);
        } catch (IOException e) {
            System.out.println("Error generating diagram file: " + e.getMessage());
        }
    }

    private void generatePlantUMLImage(String umlContent, String fileName) {
        try {
            SourceStringReader reader = new SourceStringReader(umlContent);
            FileOutputStream output = new FileOutputStream(fileName);
            reader.outputImage(output);
        } catch (IOException e) {
            System.out.println("Error generating UML image: " + e.getMessage());
        }
    }

    private void generateTextFile(String content, String fileName) {
        try (FileWriter writer = new FileWriter(fileName)) {
            writer.write(content);
        } catch (IOException e) {
            System.out.println("Error generating text file: " + e.getMessage());
        }
    }

    private String getExternalServiceName(String url) {
        try {
            java.net.URL parsedUrl = new java.net.URL(url);
            return parsedUrl.getHost();
        } catch (java.net.MalformedURLException e) {
            return "ExternalService";
        }
    }

    private boolean isRepository(ClassNode classNode) {
        return classNode.interfaces.stream().anyMatch(REPOSITORY_MARKERS::contains);
    }

    private boolean isWebClient(ClassNode classNode) {
        return classNode.fields.stream().anyMatch(field -> WEBCLIENT_MARKERS.stream().anyMatch(marker -> field.desc.contains(marker)));
    }
}