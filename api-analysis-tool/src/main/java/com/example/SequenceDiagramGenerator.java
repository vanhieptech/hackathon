package com.example;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.model.FilterOption;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.stream.Collectors;
import java.net.URL;
import java.net.MalformedURLException;

public class SequenceDiagramGenerator {
  private static final Logger logger = LoggerFactory.getLogger(SequenceDiagramGenerator.class);
  private final Map<String, String> configProperties;
  private final Map<String, Set<String>> classImports;
  private final Map<String, ClassNode> implementationCache = new HashMap<>();
  private final Set<String> processedMethods = new HashSet<>();
  private final List<String> orderedParticipants = new ArrayList<>();
  private final Map<String, String> implToInterfaceMap = new HashMap<>();
  private final Map<String, String> classToHostMap = new HashMap<>();
  private final Map<String, Set<String>> methodToAnnotations = new HashMap<>();
  private final boolean useNamingConventions;
  private final List<ImplementationMatcher> customRules;
  private final Map<String, Set<String>> serviceDependencies = new HashMap<>();
  private final APIInventoryExtractor apiInventoryExtractor;

  public interface ImplementationMatcher {
    boolean matches(ClassNode classNode, String interfaceName);
  }

  public SequenceDiagramGenerator(Map<String, String> configProperties, Map<String, Set<String>> classImports,
      List<ClassNode> allClasses) {
    logger.info("Initializing SequenceDiagramGenerator");
    this.configProperties = configProperties;
    this.classImports = classImports;
    this.useNamingConventions = Boolean.parseBoolean(configProperties.getOrDefault("use.naming.conventions", "true"));
    this.customRules = initializeCustomRules();
    this.apiInventoryExtractor = new APIInventoryExtractor(configProperties, "", allClasses);
  }

  private List<ImplementationMatcher> initializeCustomRules() {
    // Initialize custom rules for implementation matching
    List<ImplementationMatcher> rules = new ArrayList<>();
    // Add custom rules here if needed
    return rules;
  }

  public String generateSequenceDiagram(List<APIInfo> apiInfoList, FilterOption filterOption) {
    StringBuilder sb = new StringBuilder();
    sb.append("@startuml\n");

    appendParticipants(sb, apiInfoList);

    for (APIInfo apiInfo : apiInfoList) {
      generateSequenceForService(sb, apiInfo, apiInfoList, filterOption);
    }

    sb.append("@enduml");
    return sb.toString();
  }

  private void appendParticipants(StringBuilder sb, List<APIInfo> apiInfoList) {
    sb.append("participant Client\n");
    for (APIInfo apiInfo : apiInfoList) {
      sb.append("participant \"").append(apiInfo.getServiceName()).append("\" as ")
          .append(sanitizeParticipantName(apiInfo.getServiceName())).append("\n");
    }
    sb.append("\n");
  }

  private void generateSequenceForService(StringBuilder sb, APIInfo apiInfo, List<APIInfo> allApiInfo,
      FilterOption filterOption) {
    String sanitizedServiceName = sanitizeParticipantName(apiInfo.getServiceName());
    for (APIInfo.ExposedAPI exposedAPI : apiInfo.getExposedApis()) {
      sb.append("Client -> ").append(sanitizedServiceName).append(": ")
          .append(exposedAPI.getHttpMethod()).append(" ")
          .append(exposedAPI.getPath()).append("\n");
      sb.append("activate ").append(sanitizedServiceName).append("\n");

      if (filterOption != FilterOption.INTER_SERVICE_ONLY) {
        for (APIInfo.ExternalAPI externalAPI : exposedAPI.getExternalAPIs()) {
          String targetService = findTargetService(externalAPI.getUrl(), allApiInfo);
          String sanitizedTargetService = sanitizeParticipantName(targetService);

          if (externalAPI.isAsync() && filterOption == FilterOption.INCLUDE_ASYNC_CALLS) {
            sb.append(sanitizedServiceName).append(" ->> ").append(sanitizedTargetService).append(": ")
                .append(externalAPI.getHttpMethod()).append(" ")
                .append(externalAPI.getUrl()).append(" (async)\n");
          } else if (!externalAPI.isAsync() || filterOption == FilterOption.INCLUDE_EXTERNAL_CALLS) {
            sb.append(sanitizedServiceName).append(" -> ").append(sanitizedTargetService).append(": ")
                .append(externalAPI.getHttpMethod()).append(" ")
                .append(externalAPI.getUrl()).append("\n");
            sb.append("activate ").append(sanitizedTargetService).append("\n");
            sb.append(sanitizedTargetService).append(" --> ").append(sanitizedServiceName).append(": Response\n");
            sb.append("deactivate ").append(sanitizedTargetService).append("\n");
          }
        }
      }

      sb.append(sanitizedServiceName).append(" --> Client: Response\n");
      sb.append("deactivate ").append(sanitizedServiceName).append("\n\n");
    }
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

  private String extractServiceNameFromUrl(String url) {
    try {
      URL parsedUrl = new URL(url);
      String host = parsedUrl.getHost();
      String[] parts = host.split("\\.");
      if (parts.length > 0) {
        return parts[0];
      }
    } catch (MalformedURLException e) {
      logger.warn("Failed to parse URL: {}", url, e);
    }
    return "UnknownService";
  }

  private List<ExternalCallInfo> findExternalCalls(List<ClassNode> allClasses) {
    ExternalCallScanner scanner = new ExternalCallScanner(configProperties, classImports);
    return scanner.findExternalCalls(allClasses);
  }

  private String getSimpleClassName(String fullClassName) {
    logger.trace("Getting simple class name for: {}", fullClassName);
    if (fullClassName == null || fullClassName.isEmpty()) {
      return "";
    }

    // Remove array notation if present
    int arrayIndex = fullClassName.indexOf('[');
    if (arrayIndex != -1) {
      fullClassName = fullClassName.substring(0, arrayIndex);
    }

    // Handle inner classes
    int innerClassIndex = fullClassName.lastIndexOf('$');
    if (innerClassIndex != -1) {
      fullClassName = fullClassName.substring(innerClassIndex + 1);
    }

    // Extract the class name after the last dot or slash
    int lastSeparatorIndex = Math.max(fullClassName.lastIndexOf('.'), fullClassName.lastIndexOf('/'));
    String simpleName = lastSeparatorIndex != -1 ? fullClassName.substring(lastSeparatorIndex + 1) : fullClassName;

    // Check if this class is an implementation and return the interface name if it
    // exists
    String result = implToInterfaceMap.getOrDefault(simpleName, simpleName);
    logger.trace("Simple class name result: {}", result);
    return result;
  }
}