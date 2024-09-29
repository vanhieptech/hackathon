package com.example;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ExternalCallScanner {
  private final Map<String, String> configProperties;
  private final Map<String, Set<String>> classImports;

  public ExternalCallScanner(Map<String, String> configProperties, Map<String, Set<String>> classImports) {
    this.configProperties = configProperties;
    this.classImports = classImports;
  }

  public List<ExternalCallInfo> findExternalCalls(List<ClassNode> allClasses) {
    List<ExternalCallInfo> externalCalls = new ArrayList<>();

    for (ClassNode classNode : allClasses) {
      for (MethodNode methodNode : classNode.methods) {
        externalCalls.addAll(scanMethodForExternalCalls(classNode, methodNode));
      }
    }

    return externalCalls;
  }

  private List<ExternalCallInfo> scanMethodForExternalCalls(ClassNode classNode, MethodNode methodNode) {
    List<ExternalCallInfo> externalCalls = new ArrayList<>();

    for (AbstractInsnNode insn : methodNode.instructions) {
      if (insn instanceof MethodInsnNode) {
        MethodInsnNode methodInsn = (MethodInsnNode) insn;
        ExternalCallInfo externalCall = extractExternalCallInfo(classNode, methodNode, methodInsn);
        if (externalCall != null) {
          externalCalls.add(externalCall);
        }
      }
    }

    return externalCalls;
  }

  private ExternalCallInfo extractExternalCallInfo(ClassNode classNode, MethodNode methodNode,
      MethodInsnNode methodInsn) {
    String owner = methodInsn.owner.replace('/', '.');
    String method = methodInsn.name;

    if (isHttpClientMethod(owner, method)) {
      String url = extractUrlFromMethod(methodNode, methodInsn);
      if (url == null) {
        url = extractUrlFromConfig(classNode);
      }

      if (url != null) {
        String httpMethod = inferHttpMethod(method);
        return new ExternalCallInfo(url, httpMethod, new ArrayList<>(),
            classNode.name + "." + methodNode.name, "Unknown",
            methodNode.name, "", getClientLibrary(owner),
            method, extractServiceName(url),
            "External API call", classNode.name);
      }
    }

    return null;
  }

  private boolean isHttpClientMethod(String owner, String method) {
    return (owner.contains("RestTemplate") && (method.startsWith("get") || method.startsWith("post")
        || method.startsWith("put") || method.startsWith("delete"))) ||
        (owner.contains("WebClient")
            && (method.equals("get") || method.equals("post") || method.equals("put") || method.equals("delete")))
        ||
        (owner.contains("HttpClient") && method.equals("execute")) ||
        (owner.contains("OkHttpClient") && method.equals("newCall")) ||
        (owner.endsWith("Client") && !owner.startsWith("java.") && !owner.startsWith("javax."));
  }

  private String extractUrlFromMethod(MethodNode methodNode, MethodInsnNode methodInsn) {
    // This is a simplified version. In a real-world scenario, you'd need to do more
    // sophisticated
    // data flow analysis to track the URL parameter.
    for (AbstractInsnNode insn : methodNode.instructions) {
      if (insn instanceof LdcInsnNode && insn.getNext() == methodInsn) {
        LdcInsnNode ldcInsn = (LdcInsnNode) insn;
        if (ldcInsn.cst instanceof String) {
          String potentialUrl = (String) ldcInsn.cst;
          if (potentialUrl.startsWith("http://") || potentialUrl.startsWith("https://")) {
            return potentialUrl;
          }
        }
      }
    }
    return null;
  }

  private String extractUrlFromConfig(ClassNode classNode) {
    Set<String> imports = classImports.get(classNode.name);
    if (imports != null) {
      for (String importStatement : imports) {
        if (importStatement.contains("@Value")) {
          // Extract the property name from the @Value annotation
          Pattern pattern = Pattern.compile("\\$\\{(.+?)\\}");
          Matcher matcher = pattern.matcher(importStatement);
          if (matcher.find()) {
            String propertyName = matcher.group(1);
            return configProperties.get(propertyName);
          }
        }
      }
    }
    return null;
  }

  private String inferHttpMethod(String methodName) {
    if (methodName.startsWith("get"))
      return "GET";
    if (methodName.startsWith("post"))
      return "POST";
    if (methodName.startsWith("put"))
      return "PUT";
    if (methodName.startsWith("delete"))
      return "DELETE";
    return "UNKNOWN";
  }

  private String getClientLibrary(String owner) {
    if (owner.contains("RestTemplate") || owner.contains("WebClient"))
      return "Spring";
    if (owner.contains("HttpClient"))
      return "Apache HttpClient";
    if (owner.contains("OkHttpClient"))
      return "OkHttp";
    if (owner.endsWith("Client"))
      return "Feign";
    return "Unknown";
  }

  private String extractServiceName(String url) {
    try {
      String host = new java.net.URL(url).getHost();
      String[] parts = host.split("\\.");
      return parts[parts.length - 2];
    } catch (Exception e) {
      return "Unknown";
    }
  }
}