package com.analyzer.service;

import com.analyzer.model.UmlDiagram;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class UmlGeneratorService {

    public List<UmlDiagram> generateUmlDiagrams(Map<String, byte[]> projectFiles) {
        List<UmlDiagram> diagrams = new ArrayList<>();
        StringBuilder classDiagramContent = new StringBuilder("classDiagram\n");
        StringBuilder sequenceDiagramContent = new StringBuilder("sequenceDiagram\n");
        Map<String, Set<String>> classRelations = new HashMap<>();

        for (Map.Entry<String, byte[]> entry : projectFiles.entrySet()) {
            if (entry.getKey().endsWith(".java")) {
                try {
                    CompilationUnit cu = StaticJavaParser.parse(new String(entry.getValue()));
                    processCompilationUnit(cu, classDiagramContent, sequenceDiagramContent, classRelations);
                } catch (Exception e) {
                    System.err.println("Error processing file " + entry.getKey() + ": " + e.getMessage());
                }
            }
        }

        // Add relationships to class diagram
        for (Map.Entry<String, Set<String>> entry : classRelations.entrySet()) {
            for (String relatedClass : entry.getValue()) {
                classDiagramContent.append("    ").append(entry.getKey()).append(" --> ").append(relatedClass).append("\n");
            }
        }

        diagrams.add(new UmlDiagram(UUID.randomUUID().toString(), "Class Diagram", classDiagramContent.toString()));
        diagrams.add(new UmlDiagram(UUID.randomUUID().toString(), "Sequence Diagram", sequenceDiagramContent.toString()));

        return diagrams;
    }

    private void processCompilationUnit(CompilationUnit cu, StringBuilder classDiagramContent,
                                        StringBuilder sequenceDiagramContent, Map<String, Set<String>> classRelations) {
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(coid -> {
            String className = coid.getNameAsString();
            classDiagramContent.append("    class ").append(className).append(" {\n");

            if (coid.isInterface()) {
                classDiagramContent.append("        <<interface>>\n");
            }

            // Add fields
            coid.getFields().forEach(field -> {
                classDiagramContent.append("        ")
                        .append(field.getVariables().get(0).getNameAsString())
                        .append(" : ")
                        .append(field.getCommonType().asString())
                        .append("\n");
            });

            // Add methods
            coid.getMethods().forEach(method -> {
                classDiagramContent.append("        ")
                        .append(method.getNameAsString())
                        .append("(")
                        .append(method.getParameters().stream()
                                .map(p -> p.getType().asString())
                                .reduce((a, b) -> a + ", " + b)
                                .orElse(""))
                        .append(") ")
                        .append(method.getType().asString())
                        .append("\n");

                // Generate sequence diagram
                generateSequenceDiagram(sequenceDiagramContent, className, method);
            });

            classDiagramContent.append("    }\n");

            // Process class relationships
            processClassRelationships(coid, classRelations);
        });
    }

    private void processClassRelationships(ClassOrInterfaceDeclaration coid, Map<String, Set<String>> classRelations) {
        String className = coid.getNameAsString();
        Set<String> relations = new HashSet<>();

        // Check superclass
        coid.getExtendedTypes().forEach(extendedType -> relations.add(extendedType.getNameAsString()));

        // Check implemented interfaces
        coid.getImplementedTypes().forEach(implementedType -> relations.add(implementedType.getNameAsString()));

        // Check field types
        coid.getFields().forEach(field -> relations.add(field.getCommonType().asString()));

        // Check method return types and parameter types
        coid.getMethods().forEach(method -> {
            relations.add(method.getType().asString());
            method.getParameters().forEach(param -> relations.add(param.getType().asString()));
        });

        // Remove self-references and common types
        relations.remove(className);
        relations.removeIf(this::isCommonType);

        if (!relations.isEmpty()) {
            classRelations.put(className, relations);
        }
    }

    private boolean isCommonType(String type) {
        Set<String> commonTypes = new HashSet<>(Arrays.asList(
                "String", "Integer", "Long", "Double", "Float", "Boolean", "Byte", "Short", "Character",
                "Object", "List", "Map", "Set", "Collection"
        ));
        return commonTypes.contains(type) || type.startsWith("java.") || type.startsWith("javax.");
    }

    private void generateSequenceDiagram(StringBuilder sequenceDiagramContent, String className, MethodDeclaration method) {
        sequenceDiagramContent.append("    ").append(className).append("->").append(className).append(": ")
                .append(method.getNameAsString()).append("(");
        method.getParameters().forEach(param ->
                sequenceDiagramContent.append(param.getType().asString()).append(" ").append(param.getNameAsString()).append(", ")
        );
        if (method.getParameters().size() > 0) {
            sequenceDiagramContent.setLength(sequenceDiagramContent.length() - 2); // Remove last ", "
        }
        sequenceDiagramContent.append(")\n");
    }
}