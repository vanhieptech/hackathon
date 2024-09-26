package com.example.apianalysistool;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.Map;
import java.util.HashMap;

public class APIScanner {

    private static final Map<String, String> MAPPING_ANNOTATIONS = new HashMap<>();
    static {
        MAPPING_ANNOTATIONS.put("Lorg/springframework/web/bind/annotation/GetMapping;", "GET");
        MAPPING_ANNOTATIONS.put("Lorg/springframework/web/bind/annotation/PostMapping;", "POST");
        MAPPING_ANNOTATIONS.put("Lorg/springframework/web/bind/annotation/PutMapping;", "PUT");
        MAPPING_ANNOTATIONS.put("Lorg/springframework/web/bind/annotation/DeleteMapping;", "DELETE");
        MAPPING_ANNOTATIONS.put("Lorg/springframework/web/bind/annotation/PatchMapping;", "PATCH");
        MAPPING_ANNOTATIONS.put("Lorg/springframework/web/bind/annotation/RequestMapping;", "REQUEST");
    }

    public List<APIInfo> findExposedAPIs(List<ClassNode> classes) {
        List<APIInfo> apis = new ArrayList<>();
        for (ClassNode classNode : classes) {
            String classLevelPath = getClassLevelPath(classNode);
            for (MethodNode method : classNode.methods) {
                APIInfo apiInfo = scanMethod(classNode, method, classLevelPath);
                if (apiInfo != null) {
                    apis.add(apiInfo);
                }
            }
        }
        return apis;
    }

    private String getClassLevelPath(ClassNode classNode) {
        if (classNode.visibleAnnotations != null) {
            for (AnnotationNode annotation : classNode.visibleAnnotations) {
                if (annotation.desc.equals("Lorg/springframework/web/bind/annotation/RequestMapping;")) {
                    return getPathFromAnnotation(annotation);
                }
            }
        }
        return "";
    }

    private APIInfo scanMethod(ClassNode classNode, MethodNode method, String classLevelPath) {
        if (method.visibleAnnotations != null) {
            for (AnnotationNode annotation : method.visibleAnnotations) {
                String httpMethod = MAPPING_ANNOTATIONS.get(annotation.desc);
                if (httpMethod != null) {
                    String path = getPathFromAnnotation(annotation);
                    String fullPath = classLevelPath + path;
                    Type[] argumentTypes = Type.getArgumentTypes(method.desc);
                    String[] parameters = Arrays.stream(argumentTypes)
                            .map(Type::getClassName)
                            .toArray(String[]::new);
                    String returnType = Type.getReturnType(method.desc).getClassName();

                    return new APIInfo(
                            classNode.name + "." + method.name,
                            httpMethod,
                            fullPath,
                            parameters,
                            returnType
                    );
                }
            }
        }
        return null;
    }

    private String getPathFromAnnotation(AnnotationNode annotation) {
        if (annotation.values != null) {
            for (int i = 0; i < annotation.values.size(); i += 2) {
                String name = (String) annotation.values.get(i);
                Object value = annotation.values.get(i + 1);
                if ("value".equals(name) || "path".equals(name)) {
                    if (value instanceof String) {
                        return (String) value;
                    } else if (value instanceof List) {
                        List<?> list = (List<?>) value;
                        if (!list.isEmpty() && list.get(0) instanceof String) {
                            return (String) list.get(0);
                        }
                    } else if (value instanceof String[]) {
                        String[] array = (String[]) value;
                        if (array.length > 0) {
                            return array[0];
                        }
                    }
                }
            }
        }
        return "";
    }
}