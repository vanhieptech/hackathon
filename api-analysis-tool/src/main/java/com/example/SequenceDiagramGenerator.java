package com.example;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.model.APIInfo;

public class SequenceDiagramGenerator {
  private static final Logger logger = LoggerFactory.getLogger(SequenceDiagramGenerator.class);
  private final Map<String, String> configProperties;
  private final Map<String, Set<String>> classImports;
  private final boolean useNamingConventions;
  private final List<ImplementationMatcher> customRules;

  public interface ImplementationMatcher {
    boolean matches(ClassNode classNode, String interfaceName);
  }

  public SequenceDiagramGenerator(Map<String, String> configProperties, Map<String, Set<String>> classImports,
      List<ClassNode> allClasses) {
    this.configProperties = configProperties;
    this.classImports = classImports;
    this.useNamingConventions = Boolean.parseBoolean(configProperties.getOrDefault("use.naming.conventions", "true"));
    this.customRules = initializeCustomRules();
  }

  private List<ImplementationMatcher> initializeCustomRules() {
    // Initialize custom rules for implementation matching
    List<ImplementationMatcher> rules = new ArrayList<>();
    // Add custom rules here if needed
    return rules;
  }

  public String generateSequenceDiagram(List<APIInfo> apiInfoList, DiagramOptions options) {
    StringBuilder sb = new StringBuilder();
    sb.append("@startuml\n");

    appendParticipants(sb, apiInfoList);

    for (APIInfo apiInfo : apiInfoList) {
      generateSequenceForService(sb, apiInfo, apiInfoList, options);
    }

    sb.append("@enduml");
    return sb.toString();
  }

  private void appendParticipants(StringBuilder sb, List<APIInfo> apiInfoList) {
    Map<String, List<APIInfo>> systemGroups = new HashMap<>();

    for (APIInfo apiInfo : apiInfoList) {
      String systemName = extractSystemName(apiInfo.getServiceName());
      systemGroups.computeIfAbsent(systemName, k -> new ArrayList<>()).add(apiInfo);
    }

    for (Map.Entry<String, List<APIInfo>> entry : systemGroups.entrySet()) {
      String systemName = entry.getKey();
      List<APIInfo> services = entry.getValue();

      sb.append("box \"").append(systemName.toUpperCase()).append(" System\"\n");
      for (APIInfo apiInfo : services) {
        String serviceName = sanitizeParticipantName(apiInfo.getServiceName());
        sb.append("  participant \"").append(apiInfo.getServiceName()).append("\" as ").append(serviceName)
            .append("\n");
      }
      sb.append("end box\n\n");
    }
  }

  private void generateSequenceForService(StringBuilder sb, APIInfo apiInfo, List<APIInfo> allApiInfo,
      DiagramOptions options) {
    String sanitizedServiceName = sanitizeParticipantName(apiInfo.getServiceName());
    String systemName = extractSystemName(apiInfo.getServiceName());

    for (APIInfo.ExposedAPI exposedAPI : apiInfo.getExposedApis()) {
      sb.append(systemName).append(" -> ").append(sanitizedServiceName).append(": ")
          .append(exposedAPI.getHttpMethod()).append(" ")
          .append(exposedAPI.getPath()).append("\n");
      sb.append("activate ").append(sanitizedServiceName).append("\n");

      generateExternalAPICalls(sb, exposedAPI, allApiInfo, options);

      sb.append(sanitizedServiceName).append(" --> ").append(systemName).append(": Response\n");
      sb.append("deactivate ").append(sanitizedServiceName).append("\n\n");
    }
  }

  private void generateExternalAPICalls(StringBuilder sb, APIInfo.ExposedAPI exposedAPI, List<APIInfo> allApiInfo,
      DiagramOptions options) {
    String sourceSystem = extractSystemName(exposedAPI.getServiceName());
    for (APIInfo.ExternalAPI externalAPI : exposedAPI.getExternalApis()) {
      String targetService = findTargetService(externalAPI.getPath(), allApiInfo);
      String targetSystem = extractSystemName(targetService);
      String sanitizedTargetService = sanitizeParticipantName(targetService);

      String arrow = externalAPI.isAsync() ? "->>" : "->";
      sb.append(sourceSystem).append(" ").append(arrow).append(" ")
          .append(targetSystem).append(": ")
          .append(externalAPI.getHttpMethod()).append(" ")
          .append(externalAPI.getPath())
          .append(" (").append(sanitizedTargetService).append(")")
          .append(externalAPI.isAsync() ? " (async)" : "").append("\n");

      if (!externalAPI.isAsync()) {
        sb.append(targetSystem).append(" --> ").append(sourceSystem).append(": Response\n");
      }
    }
  }

  private String extractSystemName(String serviceName) {
    String[] parts = serviceName.split("-");
    return parts.length > 0 ? parts[0] : "";
  }

  private String sanitizeParticipantName(String name) {
    return name.replaceAll("[^a-zA-Z0-9]", "_");
  }

  private String findTargetService(String url, List<APIInfo> allApiInfo) {
    for (APIInfo apiInfo : allApiInfo) {
      for (APIInfo.ExposedAPI exposedAPI : apiInfo.getExposedApis()) {
        if (url.endsWith(exposedAPI.getPath())) {
          return apiInfo.getServiceName();
        }
      }
    }
    return "ExternalAPI";
  }

  public static class DiagramOptions {
    private boolean detailedView;
    private boolean includeAsyncCalls;
    private boolean includeExternalCalls;

    public DiagramOptions(boolean detailedView, boolean includeAsyncCalls, boolean includeExternalCalls) {
      this.detailedView = detailedView;
      this.includeAsyncCalls = includeAsyncCalls;
      this.includeExternalCalls = includeExternalCalls;
    }

    public boolean isDetailedView() {
      return detailedView;
    }

    public boolean includeExternalCall(APIInfo.ExternalAPI externalAPI) {
      return (includeAsyncCalls && externalAPI.isAsync()) ||
          (includeExternalCalls && !externalAPI.isAsync());
    }
  }
}