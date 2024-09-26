package com.analyzer.service;

import com.analyzer.model.UmlDiagram;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class UmlGeneratorService {
    private static final Logger logger = LoggerFactory.getLogger(UmlGeneratorService.class);
    private Map<String, ClassOrInterfaceDeclaration> classMap = new HashMap<>();

    public List<UmlDiagram> generateUmlDiagrams(Map<String, byte[]> projectFiles) {
        List<UmlDiagram> diagrams = new ArrayList<>();
        StringBuilder classDiagramContent = new StringBuilder("classDiagram\n");
        StringBuilder sequenceDiagramContent = new StringBuilder();
        Map<String, Set<String>> classRelations = new HashMap<>();

        // First pass: build classMap
        for (Map.Entry<String, byte[]> entry : projectFiles.entrySet()) {
            if (entry.getKey().endsWith(".java")) {
                try {
                    CompilationUnit cu = StaticJavaParser.parse(new String(entry.getValue()));
                    cu.findAll(ClassOrInterfaceDeclaration.class).forEach(coid ->
                            classMap.put(coid.getNameAsString(), coid)
                    );
                } catch (Exception e) {
                    logger.error("Error processing file {}: {}", entry.getKey(), e.getMessage());
                }
            }
        }

        // Second pass: generate diagrams
        for (ClassOrInterfaceDeclaration coid : classMap.values()) {
            processClassDiagram(coid, classDiagramContent);
            processClassRelationships(coid, classRelations);
            if (isController(coid)) {
                generateSequenceDiagram(coid, sequenceDiagramContent);
            }
        }

        // Add relationships to class diagram
        for (Map.Entry<String, Set<String>> entry : classRelations.entrySet()) {
            for (String relatedClass : entry.getValue()) {
                classDiagramContent.append("    ").append(entry.getKey()).append(" --> ").append(relatedClass).append("\n");
            }
        }

        diagrams.add(new UmlDiagram(UUID.randomUUID().toString(), "Class Diagram", classDiagramContent.toString()));
        logger.info("Class Diagram: \n{}", classDiagramContent);
        diagrams.add(new UmlDiagram(UUID.randomUUID().toString(), "Sequence Diagram", sequenceDiagramContent.toString()));
        logger.info("Sequence Diagram: \n{}", sequenceDiagramContent);

        return diagrams;
    }

    private boolean isController(ClassOrInterfaceDeclaration coid) {
        return coid.getAnnotations().stream()
                .anyMatch(a -> a.getNameAsString().equals("RestController") ||
                        a.getNameAsString().equals("Controller"));
    }

    private void processClassRelationships(ClassOrInterfaceDeclaration coid, Map<String, Set<String>> classRelations) {
        String className = coid.getNameAsString();
        Set<String> relations = new HashSet<>();

        // Check superclass
        coid.getExtendedTypes().forEach(extendedType ->
                relations.add(className + " --|> " + extendedType.getNameAsString())
        );

        // Check implemented interfaces
        coid.getImplementedTypes().forEach(implementedType ->
                relations.add(className + " ..|> " + implementedType.getNameAsString())
        );


        // Check field types
        coid.getFields().forEach(field -> relations.add(cleanType(field.getCommonType().asString())));

        // Check method return types and parameter types
        coid.getMethods().forEach(method -> {
            relations.add(cleanType(method.getType().asString()));  // Clean the return type
            method.getParameters().forEach(param -> relations.add(cleanType(param.getType().asString())));  // Clean the parameter types
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

    private String cleanType(String type) {
        if (type.contains("<")) {
            return type.substring(0, type.indexOf('<'));
        }
        return type.equals("void") ? "Void" : type;
    }

    private void processClassDiagram(ClassOrInterfaceDeclaration coid, StringBuilder classDiagramContent) {
        String className = coid.getNameAsString();
        classDiagramContent.append("    class ").append(className).append(" {\n");

        if (coid.isInterface()) {
            classDiagramContent.append("        <<interface>>\n");
        }

        // Add fields with visibility
        coid.getFields().forEach(field -> {
            String visibility = field.isPrivate() ? "-" : field.isPublic() ? "+" : "#";
            classDiagramContent.append("        ")
                    .append(visibility)
                    .append(field.getVariables().get(0).getNameAsString())
                    .append(" : ")
                    .append(cleanType(field.getCommonType().asString()))
                    .append("\n");
        });

        // Add methods with visibility
        coid.getMethods().forEach(method -> {
            String visibility = method.isPrivate() ? "-" : method.isPublic() ? "+" : "#";
            classDiagramContent.append("        ")
                    .append(visibility)
                    .append(method.getNameAsString())
                    .append("(")
                    .append(method.getParameters().stream()
                            .map(p -> cleanType(p.getType().asString()))
                            .reduce((a, b) -> a + ", " + b)
                            .orElse(""))
                    .append(") ")
                    .append(cleanType(method.getType().asString()))
                    .append("\n");
        });

        classDiagramContent.append("    }\n");
    }

    private void generateSequenceDiagram(ClassOrInterfaceDeclaration coid, StringBuilder sequenceDiagramContent) {
        String controllerName = coid.getNameAsString();
        sequenceDiagramContent.append("sequenceDiagram\n");
        sequenceDiagramContent.append("    participant Client\n");
        sequenceDiagramContent.append("    participant ").append(controllerName).append("\n");

        coid.getMethods().forEach(method -> {
            Optional<RestEndpoint> restEndpoint = getRestEndpoint(method);
            if (restEndpoint.isPresent()) {
                String methodName = method.getNameAsString();
                sequenceDiagramContent.append("    Client->>+").append(controllerName).append(": ")
                        .append(restEndpoint.get().method).append(" ")
                        .append(restEndpoint.get().path).append("\n");

                processMethodBody(method, controllerName, sequenceDiagramContent, "    ", new HashSet<>());

                sequenceDiagramContent.append("    ").append(controllerName).append("-->>-Client: Response\n");
                sequenceDiagramContent.append("\n");
            }
        });
    }

    private void processMethodBody(MethodDeclaration method, String className, StringBuilder sequenceDiagramContent, String indent, Set<String> processedMethods) {
        String methodSignature = className + "." + method.getNameAsString();
        if (processedMethods.contains(methodSignature)) {
            return; // Prevent infinite recursion
        }
        processedMethods.add(methodSignature);

        method.findAll(MethodCallExpr.class).forEach(methodCall ->
                processMethodCall(methodCall, className, sequenceDiagramContent, indent, processedMethods)
        );
    }

    private void processMethodCall(MethodCallExpr methodCall, String className, StringBuilder sequenceDiagramContent, String indent, Set<String> processedMethods) {
        try {
            ResolvedMethodDeclaration resolvedMethod = methodCall.resolve();
            String calledClassName = resolvedMethod.getClassName();
            String calledMethodName = resolvedMethod.getName();

            if (!calledClassName.equals(className) && !isCommonType(calledClassName)) {
                String shortCalledClassName = getShortClassName(calledClassName);
                addParticipantIfNotExists(sequenceDiagramContent, shortCalledClassName);

                sequenceDiagramContent.append(indent).append(getShortClassName(className)).append("->>+")
                        .append(shortCalledClassName).append(": ")
                        .append(calledMethodName).append("()\n");

                ClassOrInterfaceDeclaration calledClass = classMap.get(calledClassName);
                if (calledClass != null) {
                    Optional<MethodDeclaration> calledMethod = calledClass.getMethods().stream()
                            .filter(m -> m.getNameAsString().equals(calledMethodName))
                            .findFirst();
                    calledMethod.ifPresent(m -> processMethodBody(m, calledClassName, sequenceDiagramContent, indent + "    ", processedMethods));
                }

                if (isRepository(calledClass)) {
                    addParticipantIfNotExists(sequenceDiagramContent, "DB");
                    sequenceDiagramContent.append(indent + "    ").append(shortCalledClassName)
                            .append("->>+DB: Query\n");
                    sequenceDiagramContent.append(indent + "    ").append("DB-->>-")
                            .append(shortCalledClassName).append(": Result\n");
                }

                sequenceDiagramContent.append(indent).append(shortCalledClassName).append("-->>-")
                        .append(getShortClassName(className)).append(": Return data\n");
            }
        } catch (Exception e) {
            // If unable to resolve, just add the method call
            sequenceDiagramContent.append(indent).append(getShortClassName(className)).append("->")
                    .append(getShortClassName(className)).append(": ")
                    .append(methodCall.getNameAsString()).append("()\n");
        }
    }

    private String getShortClassName(String fullClassName) {
        return fullClassName.substring(fullClassName.lastIndexOf('.') + 1);
    }

    private void addParticipantIfNotExists(StringBuilder sequenceDiagramContent, String participant) {
        if (!sequenceDiagramContent.toString().contains("participant " + participant)) {
            sequenceDiagramContent.insert(sequenceDiagramContent.indexOf("\n") + 1, 
                "    participant " + participant + "\n");
        }
    }

    private boolean isRepository(ClassOrInterfaceDeclaration coid) {
        return coid != null && (coid.getAnnotationByName("Repository").isPresent() ||
                coid.getImplementedTypes().stream().anyMatch(t -> t.getNameAsString().contains("Repository")));
    }

    private Optional<RestEndpoint> getRestEndpoint(MethodDeclaration method) {
        for (var annotation : method.getAnnotations()) {
            String annotationName = annotation.getNameAsString();
            if (annotationName.endsWith("Mapping")) {
                String httpMethod = annotationName.replace("Mapping", "").toUpperCase();
                if (httpMethod.isEmpty()) httpMethod = "GET";
                String path = annotation.getChildNodes().stream()
                        .filter(node -> node.toString().startsWith("value"))
                        .findFirst()
                        .map(node -> node.toString().replaceAll("value ?= ?\"(.*)\"", "$1"))
                        .orElse("/");
                return Optional.of(new RestEndpoint(httpMethod, path));
            }
        }
        return Optional.empty();
    }

    private static class RestEndpoint {
        String method;
        String path;

        RestEndpoint(String method, String path) {
            this.method = method;
            this.path = path;
        }
    }
}