package com.example;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.*;

public class DependencyAnalyzer {

  public List<ExternalCallInfo> analyzeExternalCalls(List<ClassNode> allClasses) {
    List<ExternalCallInfo> externalCalls = new ArrayList<>();

    for (ClassNode classNode : allClasses) {
      for (MethodNode methodNode : classNode.methods) {
        for (AbstractInsnNode insn : methodNode.instructions) {
          if (insn instanceof MethodInsnNode) {
            MethodInsnNode methodInsn = (MethodInsnNode) insn;
            if (isExternalCall(methodInsn)) {
              ExternalCallInfo callInfo = createExternalCallInfo(classNode, methodNode, methodInsn);
              externalCalls.add(callInfo);
            }
          }
        }
      }
    }

    return externalCalls;
  } 

  private boolean isExternalCall(MethodInsnNode methodInsn) {
    return methodInsn.owner.startsWith("org/springframework/web/client/RestTemplate") ||
        methodInsn.owner.startsWith("org/springframework/web/reactive/function/client/WebClient") ||
        methodInsn.owner.startsWith("feign/") ||
        methodInsn.owner.startsWith("okhttp3/") ||
        methodInsn.owner.startsWith("org/apache/http/client/") ||
        methodInsn.owner.startsWith("java/net/HttpURLConnection");
  }

  private ExternalCallInfo createExternalCallInfo(ClassNode classNode, MethodNode methodNode,
      MethodInsnNode methodInsn) {
    String url = extractUrl(methodNode, methodInsn);
    String httpMethod = extractHttpMethod(methodInsn);
    String purpose = classNode.name + "." + methodNode.name;
    String responseType = simplifyTypeName(Type.getReturnType(methodInsn.desc).getClassName());
    List<String> parameters = extractParameters(methodInsn);
    ExternalCallInfo externalCall = new ExternalCallInfo(url, httpMethod, purpose, responseType, parameters);
    externalCall.setCallerMethod(methodNode.name);
    return externalCall;
  }

  private String extractUrl(MethodNode methodNode, MethodInsnNode methodInsn) {
    for (AbstractInsnNode insn : methodNode.instructions) {
      if (insn instanceof LdcInsnNode && insn.getOpcode() == Opcodes.LDC) {
        LdcInsnNode ldcInsn = (LdcInsnNode) insn;
        if (ldcInsn.cst instanceof String) {
          String constant = (String) ldcInsn.cst;
          if (constant.startsWith("http://") || constant.startsWith("https://")) {
            return constant;
          }
        }
      }
    }
    return "Unknown URL";
  }

  private String extractHttpMethod(MethodInsnNode methodInsn) {
    String methodName = methodInsn.name.toLowerCase();
    if (methodName.contains("get"))
      return "GET";
    if (methodName.contains("post"))
      return "POST";
    if (methodName.contains("put"))
      return "PUT";
    if (methodName.contains("delete"))
      return "DELETE";
    if (methodName.contains("patch"))
      return "PATCH";
    return "Unknown HTTP Method";
  }

  private List<String> extractParameters(MethodInsnNode methodInsn) {
    List<String> parameters = new ArrayList<>();
    Type[] argumentTypes = Type.getArgumentTypes(methodInsn.desc);

    for (Type argType : argumentTypes) {
      String paramType = argType.getClassName();
      parameters.add(simplifyTypeName(paramType));
    }

    return parameters;
  }

  private String simplifyTypeName(String fullTypeName) {
    int lastDotIndex = fullTypeName.lastIndexOf('.');
    return lastDotIndex == -1 ? fullTypeName : fullTypeName.substring(lastDotIndex + 1);
  }
}
