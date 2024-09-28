package com.example;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.*;

public class ServiceInventoryExtractor {

  public String extractUtilizedServices(List<ClassNode> allClasses) {
    List<ServiceInfo> serviceInventory = new ArrayList<>();

    for (ClassNode classNode : allClasses) {
      for (MethodNode methodNode : classNode.methods) {
        serviceInventory.addAll(extractServiceCalls(classNode, methodNode));
      }
    }

    // Convert the List<ServiceInfo> to a String representation
    return convertServiceInventoryToString(serviceInventory);
  }

  private String convertServiceInventoryToString(List<ServiceInfo> serviceInventory) {
    StringBuilder sb = new StringBuilder();
    for (ServiceInfo info : serviceInventory) {
      sb.append(info.toString()).append("\n");
    }
    return sb.toString();
  }

  private List<ServiceInfo> extractServiceCalls(ClassNode classNode, MethodNode methodNode) {
    List<ServiceInfo> serviceCalls = new ArrayList<>();

    for (AbstractInsnNode insn : methodNode.instructions) {
      if (insn instanceof MethodInsnNode) {
        MethodInsnNode methodInsn = (MethodInsnNode) insn;
        if (isServiceCall(methodInsn)) {
          ServiceInfo serviceInfo = createServiceInfo(classNode, methodNode, methodInsn);
          serviceCalls.add(serviceInfo);
        }
      }
    }

    return serviceCalls;
  }

  private boolean isServiceCall(MethodInsnNode methodInsn) {
    return methodInsn.owner.startsWith("org/springframework/web/client/RestTemplate") ||
        methodInsn.owner.startsWith("org/springframework/web/reactive/function/client/WebClient") ||
        methodInsn.owner.startsWith("feign/") ||
        methodInsn.owner.startsWith("retrofit2/") ||
        methodInsn.owner.startsWith("javax/ws/rs/client/") ||
        methodInsn.owner.startsWith("com/google/protobuf/");
  }

  private ServiceInfo createServiceInfo(ClassNode classNode, MethodNode methodNode, MethodInsnNode methodInsn) {
    String serviceType = inferServiceType(methodInsn);
    String methodName = methodInsn.name;
    String callerMethod = classNode.name + "." + methodNode.name;
    List<String> parameters = extractParameters(methodInsn);

    return new ServiceInfo(serviceType, methodName, callerMethod, parameters);
  }

  private String inferServiceType(MethodInsnNode methodInsn) {
    if (methodInsn.owner.contains("RestTemplate") || methodInsn.owner.contains("WebClient")) {
      return "REST";
    } else if (methodInsn.owner.startsWith("feign/") || methodInsn.owner.startsWith("retrofit2/")) {
      return "REST (Declarative)";
    } else if (methodInsn.owner.startsWith("javax/ws/rs/client/")) {
      return "JAX-RS";
    } else if (methodInsn.owner.startsWith("com/google/protobuf/")) {
      return "gRPC";
    }
    return "Unknown";
  }

  private List<String> extractParameters(MethodInsnNode methodInsn) {
    List<String> parameters = new ArrayList<>();
    Type[] argumentTypes = Type.getArgumentTypes(methodInsn.desc);

    for (Type argType : argumentTypes) {
      parameters.add(argType.getClassName());
    }

    return parameters;
  }
}