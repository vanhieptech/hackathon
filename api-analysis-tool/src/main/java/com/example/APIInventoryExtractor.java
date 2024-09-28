package com.example;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.*;

public class APIInventoryExtractor {

  public List<APIInfo> extractExposedAPIs(List<ClassNode> allClasses) {
    List<APIInfo> apiInventory = new ArrayList<>();

    for (ClassNode classNode : allClasses) {
      if (isController(classNode) || isService(classNode)) {
        for (MethodNode methodNode : classNode.methods) {
          APIInfo apiInfo = extractAPIInfo(classNode, methodNode);
          if (apiInfo != null) {
            apiInventory.add(apiInfo);
          }
        }
      }
    }

    return apiInventory;
  }

  private boolean isController(ClassNode classNode) {
    return hasAnnotation(classNode, "RestController") || hasAnnotation(classNode, "Controller")
        || hasAnnotation(classNode, "Path");
  }

  private boolean isService(ClassNode classNode) {
    return hasAnnotation(classNode, "Service") || hasAnnotation(classNode, "Component")
        || hasAnnotation(classNode, "Repository");
  }

  private boolean hasAnnotation(ClassNode classNode, String annotationName) {
    return classNode.visibleAnnotations != null &&
        classNode.visibleAnnotations.stream()
            .anyMatch(an -> an.desc.contains(annotationName));
  }

  private APIInfo extractAPIInfo(ClassNode classNode, MethodNode methodNode) {
    String httpMethod = extractHttpMethod(methodNode);
    String path = extractPath(classNode, methodNode);
    String methodName = classNode.name + "." + methodNode.name;
    String returnType = Type.getReturnType(methodNode.desc).getClassName();
    List<String> parameters = extractParameters(methodNode);

    return new APIInfo(httpMethod, path, methodName, returnType, parameters);
  }

  private String extractJaxRsPath(MethodNode methodNode) {
    if (methodNode.visibleAnnotations != null) {
      for (AnnotationNode an : methodNode.visibleAnnotations) {
        if (an.desc.contains("Path") || an.desc.contains("GET") || an.desc.contains("POST") ||
            an.desc.contains("PUT") || an.desc.contains("DELETE")) {
          return extractPathFromAnnotation(an);
        }
      }
    }
    return null;
  }

  private String extractHttpMethod(MethodNode methodNode) {
    if (methodNode.visibleAnnotations != null) {
      for (AnnotationNode an : methodNode.visibleAnnotations) {
        if (an.desc.contains("GetMapping"))
          return "GET";
        if (an.desc.contains("PostMapping"))
          return "POST";
        if (an.desc.contains("PutMapping"))
          return "PUT";
        if (an.desc.contains("DeleteMapping"))
          return "DELETE";
        if (an.desc.contains("PatchMapping"))
          return "PATCH";
      }
    }
    return null;
  }

  private String extractPath(ClassNode classNode, MethodNode methodNode) {
    String classPath = extractClassPath(classNode);
    String methodPath = extractMethodPath(methodNode);

    if (classPath == null && methodPath == null)
      return null;
    if (classPath == null)
      return methodPath;
    if (methodPath == null)
      return classPath;

    return classPath + methodPath;
  }

  private String extractClassPath(ClassNode classNode) {
    if (classNode.visibleAnnotations != null) {
      for (AnnotationNode an : classNode.visibleAnnotations) {
        if (an.desc.contains("RequestMapping")) {
          return extractPathFromAnnotation(an);
        }
      }
    }
    return null;
  }

  private String extractMethodPath(MethodNode methodNode) {
    if (methodNode.visibleAnnotations != null) {
      for (AnnotationNode an : methodNode.visibleAnnotations) {
        if (an.desc.contains("Mapping")) {
          return extractPathFromAnnotation(an);
        }
      }
    }
    return null;
  }

  private String extractPathFromAnnotation(AnnotationNode an) {
    if (an.values != null) {
      for (int i = 0; i < an.values.size(); i += 2) {
        if (an.values.get(i).equals("value") || an.values.get(i).equals("path")) {
          Object pathValue = an.values.get(i + 1);
          if (pathValue instanceof String) {
            return (String) pathValue;
          } else if (pathValue instanceof List) {
            List<?> pathList = (List<?>) pathValue;
            if (!pathList.isEmpty() && pathList.get(0) instanceof String) {
              return (String) pathList.get(0);
            }
          }
        }
      }
    }
    return null;
  }

  private List<String> extractParameters(MethodNode methodNode) {
    List<String> parameters = new ArrayList<>();
    Type[] argumentTypes = Type.getArgumentTypes(methodNode.desc);

    for (Type argType : argumentTypes) {
      parameters.add(argType.getClassName());
    }

    return parameters;
  }
}
