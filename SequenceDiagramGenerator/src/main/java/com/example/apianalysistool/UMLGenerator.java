package com.example.apianalysistool;

import net.sourceforge.plantuml.SourceStringReader;

import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class UMLGenerator {

    public void generateDiagrams(List<APIInfo> apis, List<ExternalCallInfo> externalCalls) {
        generateSequenceDiagram(apis, externalCalls);
        generateClassDiagram(apis);
    }

    private void generateSequenceDiagram(List<APIInfo> apis, List<ExternalCallInfo> externalCalls) {
        String plantUML = generatePlantUMLSequenceDiagram(apis, externalCalls);
        String mermaid = generateMermaidSequenceDiagram(apis, externalCalls);

        generateDiagramFile(plantUML, "sequence_diagram.puml");
        generatePlantUMLImage(plantUML, "sequence_diagram.png");
        generateTextFile(mermaid, "sequence_diagram.mmd");
    }

    private void generateClassDiagram(List<APIInfo> apis) {
        String plantUML = generatePlantUMLClassDiagram(apis);
        String mermaid = generateMermaidClassDiagram(apis);

        generateDiagramFile(plantUML, "class_diagram.puml");
        generatePlantUMLImage(plantUML, "class_diagram.png");
        generateTextFile(mermaid, "class_diagram.mmd");
    }

    private String generatePlantUMLSequenceDiagram(List<APIInfo> apis, List<ExternalCallInfo> externalCalls) {
        StringBuilder uml = new StringBuilder("@startuml\n");
        uml.append("actor Client\n");
        uml.append("participant \"Your API\" as API\n");

        Map<String, String> externalSystems = new HashMap<>();
        AtomicInteger systemCount = new AtomicInteger(1);

        for (APIInfo api : apis) {
            uml.append("Client -> API : ").append(api.getHttpMethod()).append(" ").append(api.getPath()).append("\n");
            uml.append("activate API\n");

            for (ExternalCallInfo externalCall : externalCalls) {
                if (externalCall.getPurpose().contains(api.getMethodName())) {
                    String systemName = externalSystems.computeIfAbsent(externalCall.getUrl(), k -> "ExternalSystem" + systemCount.getAndIncrement());
                    uml.append("API -> ").append(systemName).append(" : ").append(externalCall.getHttpMethod()).append(" ").append(externalCall.getUrl()).append("\n");
                    uml.append("activate ").append(systemName).append("\n");
                    uml.append(systemName).append(" --> API : response\n");
                    uml.append("deactivate ").append(systemName).append("\n");
                }
            }

            uml.append("API --> Client : response\n");
            uml.append("deactivate API\n");
        }

        uml.append("@enduml");
        return uml.toString();
    }

    private String generateMermaidSequenceDiagram(List<APIInfo> apis, List<ExternalCallInfo> externalCalls) {
        StringBuilder mmd = new StringBuilder("sequenceDiagram\n");
        mmd.append("    actor Client\n");
        mmd.append("    participant API as Your API\n");

        Map<String, String> externalSystems = new HashMap<>();
        AtomicInteger systemCount = new AtomicInteger(1);

        for (APIInfo api : apis) {
            mmd.append("    Client->>API: ").append(api.getHttpMethod()).append(" ").append(api.getPath()).append("\n");
            mmd.append("    activate API\n");

            for (ExternalCallInfo externalCall : externalCalls) {
                if (externalCall.getPurpose().contains(api.getMethodName())) {
                    String systemName = externalSystems.computeIfAbsent(externalCall.getUrl(), k -> "ExternalSystem" + systemCount.getAndIncrement());
                    mmd.append("    API->>").append(systemName).append(": ").append(externalCall.getHttpMethod()).append(" ").append(externalCall.getUrl()).append("\n");
                    mmd.append("    activate ").append(systemName).append("\n");
                    mmd.append("    ").append(systemName).append("->>API: response\n");
                    mmd.append("    deactivate ").append(systemName).append("\n");
                }
            }

            mmd.append("    API->>Client: response\n");
            mmd.append("    deactivate API\n");
        }

        return mmd.toString();
    }

    private String generatePlantUMLClassDiagram(List<APIInfo> apis) {
        StringBuilder uml = new StringBuilder("@startuml\n");

        Map<String, StringBuilder> classes = new HashMap<>();

        for (APIInfo api : apis) {
            String className = api.getMethodName().substring(0, api.getMethodName().lastIndexOf('.'));
            StringBuilder classBuilder = classes.computeIfAbsent(className, k -> new StringBuilder("class ").append(k).append(" {\n"));

            classBuilder.append("  +").append(api.getMethodName().substring(api.getMethodName().lastIndexOf('.') + 1))
                    .append("(");

            for (int i = 0; i < api.getParameters().length; i++) {
                if (i > 0) classBuilder.append(", ");
                classBuilder.append(api.getParameters()[i]);
            }

            classBuilder.append(") : ").append(api.getReturnType()).append("\n");
        }

        for (StringBuilder classBuilder : classes.values()) {
            uml.append(classBuilder).append("}\n\n");
        }

        uml.append("@enduml");
        return uml.toString();
    }

    private String generateMermaidClassDiagram(List<APIInfo> apis) {
        StringBuilder mmd = new StringBuilder("classDiagram\n");

        Map<String, StringBuilder> classes = new HashMap<>();

        for (APIInfo api : apis) {
            String className = api.getMethodName().substring(0, api.getMethodName().lastIndexOf('.'));
            StringBuilder classBuilder = classes.computeIfAbsent(className, k -> new StringBuilder("    class ").append(k).append(" {\n"));

            classBuilder.append("        +").append(api.getMethodName().substring(api.getMethodName().lastIndexOf('.') + 1))
                    .append("(");

            for (int i = 0; i < api.getParameters().length; i++) {
                if (i > 0) classBuilder.append(", ");
                classBuilder.append(api.getParameters()[i]);
            }

            classBuilder.append(") ").append(api.getReturnType()).append("\n");
        }

        for (StringBuilder classBuilder : classes.values()) {
            mmd.append(classBuilder).append("    }\n");
        }

        return mmd.toString();
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
}