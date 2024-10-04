package com.example;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.model.APIInfo;

public class SequenceDiagramGenerator {
  private static final Logger logger = LoggerFactory.getLogger(SequenceDiagramGenerator.class);
  private final List<ImplementationMatcher> customRules;

  public interface ImplementationMatcher {
    boolean matches(ClassNode classNode, String interfaceName);
  }

  public SequenceDiagramGenerator() {
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
    sb.append("participant \"Client\"\n");
    Map<String, Set<String>> systemGroups = new HashMap<>();

    for (APIInfo apiInfo : apiInfoList) {
      addServiceToSystemGroups(systemGroups, apiInfo.getServiceName());

      for (APIInfo.ExposedAPI exposedAPI : apiInfo.getExposedApis()) {
        for (APIInfo.ExternalAPI externalAPI : exposedAPI.getExternalApis()) {
          addServiceToSystemGroups(systemGroups, externalAPI.getServiceName());
        }
      }
    }

    for (Map.Entry<String, Set<String>> entry : systemGroups.entrySet()) {
      String systemName = entry.getKey();
      Set<String> services = entry.getValue();

      sb.append("box \"").append(systemName.toUpperCase()).append(" System\"\n");
      for (String serviceName : services) {
        sb.append("  participant \"").append(serviceName).append("\"\n");
      }
      sb.append("end box\n");
    }
    sb.append("\n");
  }

  private void addServiceToSystemGroups(Map<String, Set<String>> systemGroups, String serviceName) {
    String systemName = extractSystemName(serviceName);
    systemGroups.computeIfAbsent(systemName, k -> new HashSet<>()).add(serviceName);
  }

  private String extractSystemName(String serviceName) {
    String[] parts = serviceName.split("-");
    return parts.length > 0 ? parts[0] : serviceName;
  }

  private void generateSequenceForService(StringBuilder sb, APIInfo apiInfo, List<APIInfo> allApiInfo,
      DiagramOptions options) {
    String sanitizedServiceName = sanitizeParticipantName(apiInfo.getServiceName());

    for (APIInfo.ExposedAPI exposedAPI : apiInfo.getExposedApis()) {
      sb.append("Client -> ").append(sanitizedServiceName).append(": ")
          .append(exposedAPI.getHttpMethod()).append(" ")
          .append(exposedAPI.getPath()).append("\n");
      sb.append("activate ").append(sanitizedServiceName).append("\n");

      generateExternalAPICalls(sb, exposedAPI, allApiInfo, options);

      sb.append(sanitizedServiceName).append(" --> Client: Response\n");
      sb.append("deactivate ").append(sanitizedServiceName).append("\n\n");
    }
  }

  private void generateExternalAPICalls(StringBuilder sb, APIInfo.ExposedAPI exposedAPI, List<APIInfo> allApiInfo,
      DiagramOptions options) {
    String sanitizedSourceService = sanitizeParticipantName(exposedAPI.getServiceName());

    for (APIInfo.ExternalAPI externalAPI : exposedAPI.getExternalApis()) {
      String targetService = externalAPI.getServiceName();
      String sanitizedTargetService = sanitizeParticipantName(targetService);

      if (sanitizedSourceService.equals(sanitizedTargetService)) {
        continue; // Skip self-calls
      }

      String arrow = externalAPI.isAsync() ? "->>" : "->";
      sb.append(sanitizedSourceService).append(" ").append(arrow).append(" ")
          .append(sanitizedTargetService).append(": ")
          .append(externalAPI.getHttpMethod()).append(" ")
          .append(externalAPI.getPath())
          .append(externalAPI.isAsync() ? " (async)" : "").append("\n");

      if (!externalAPI.isAsync()) {
        sb.append(sanitizedTargetService).append(" --> ").append(sanitizedSourceService).append(": Response\n");
      }
    }
  }

  private APIInfo.ExposedAPI findMatchingExposedAPI(APIInfo.ExternalAPI externalAPI, List<APIInfo> allApiInfo) {
    for (APIInfo apiInfo : allApiInfo) {
      for (APIInfo.ExposedAPI exposedAPI : apiInfo.getExposedApis()) {
        if (externalAPI.getPath().endsWith(exposedAPI.getPath()) &&
            externalAPI.getHttpMethod().equals(exposedAPI.getHttpMethod())) {
          return exposedAPI;
        }
      }
    }
    return null;
  }

  private String sanitizeParticipantName(String name) {
    return "\"" + name + "\"";
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