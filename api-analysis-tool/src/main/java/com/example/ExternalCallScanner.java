package com.example;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ExternalCallScanner {
  private static final Logger logger = LoggerFactory.getLogger(ExternalCallScanner.class);
  private final Map<String, String> configProperties;
  private final Map<String, Set<String>> classImports;

  public ExternalCallScanner(Map<String, String> configProperties, Map<String, Set<String>> classImports) {
    this.configProperties = configProperties;
    this.classImports = classImports;
  }

  public List<ExternalCallInfo> findExternalCalls(List<ClassNode> allClasses) {
    List<ExternalCallInfo> externalCalls = new ArrayList<>();

    for (ClassNode classNode : allClasses) {
      Map<String, String> classFields = extractClassFields(classNode);
      String baseUrl = extractBaseUrl(classNode, classFields);

      for (MethodNode methodNode : classNode.methods) {
        for (AbstractInsnNode insn : methodNode.instructions) {
          if (insn instanceof MethodInsnNode) {
            MethodInsnNode methodInsn = (MethodInsnNode) insn;
            if (isHttpClientMethod(methodInsn)) {
              ExternalCallInfo callInfo = extractExternalCallInfo(classNode, methodNode, methodInsn, baseUrl,
                  classFields);
              externalCalls.add(callInfo);
            }
          }
        }
      }
    }

    return externalCalls;
  }

  private Map<String, String> extractClassFields(ClassNode classNode) {
    Map<String, String> fields = new HashMap<>();
    for (FieldNode field : classNode.fields) {
      if (field.value instanceof String) {
        fields.put(field.name, (String) field.value);
      }
    }
    return fields;
  }

  private String extractBaseUrl(ClassNode classNode, Map<String, String> classFields) {
    // Check for @Value annotation on fields
    for (FieldNode field : classNode.fields) {
      if (field.visibleAnnotations != null) {
        for (AnnotationNode annotation : field.visibleAnnotations) {
          if (annotation.desc.contains("Value")) {
            List<Object> values = annotation.values;
            if (values != null && values.size() >= 2 && values.get(1) instanceof String) {
              String propertyKey = (String) values.get(1);
              propertyKey = propertyKey.replaceAll("[\\$\\{\\}]", "");
              return resolvePropertyValue(propertyKey);
            }
          }
        }
      }
    }

    // Check for @Value annotation on constructor parameters
    for (MethodNode method : classNode.methods) {
      if (method.name.equals("<init>")) {
        if (method.visibleParameterAnnotations != null) {
          for (int i = 0; i < method.visibleParameterAnnotations.length; i++) {
            List<AnnotationNode> annotations = method.visibleParameterAnnotations[i];
            if (annotations != null) {
              for (AnnotationNode annotation : annotations) {
                if (annotation.desc.contains("Value")) {
                  List<Object> values = annotation.values;
                  if (values != null && values.size() >= 2 && values.get(1) instanceof String) {
                    String propertyKey = (String) values.get(1);
                    propertyKey = propertyKey.replaceAll("[\\$\\{\\}]", "");
                    return resolvePropertyValue(propertyKey);
                  }
                }
              }
            }
          }
        }
      }
    }

    // Check for hardcoded base URL in constructor
    for (MethodNode method : classNode.methods) {
      if (method.name.equals("<init>")) {
        AbstractInsnNode[] instructions = method.instructions.toArray();
        for (AbstractInsnNode insn : instructions) {
          if (insn instanceof LdcInsnNode) {
            LdcInsnNode ldcInsn = (LdcInsnNode) insn;
            if (ldcInsn.cst instanceof String) {
              String value = (String) ldcInsn.cst;
              if (value.startsWith("http://") || value.startsWith("https://")) {
                return value;
              }
            }
          }
        }
      }
    }

    return classFields.get("baseUrl");
  }

  private String extractEndpoint(MethodNode methodNode, MethodInsnNode methodInsn, Map<String, String> classFields) {
    StringBuilder uriBuilder = new StringBuilder();
    List<String> uriParams = new ArrayList<>();

    scanPreviousInstructions(methodInsn, uriBuilder, uriParams, classFields);
    scanNextInstructions(methodInsn, uriBuilder, classFields);

    String endpoint = uriBuilder.toString();

    // Replace placeholders with actual parameters
    for (int i = 0; i < uriParams.size(); i++) {
      endpoint = endpoint.replaceFirst("\\{[^}]+\\}", uriParams.get(i));
    }

    if (!endpoint.isEmpty()) {
      logger.debug("Extracted endpoint: {}", endpoint);
      return endpoint;
    } else {
      logger.warn("Could not extract endpoint for method call: {}", methodInsn.name);
      return null;
    }
  }

  private void scanPreviousInstructions(AbstractInsnNode startInsn, StringBuilder uriBuilder,
      List<String> uriParams, Map<String, String> classFields) {
    AbstractInsnNode currentInsn = startInsn;
    int depth = 0;

    while (currentInsn != null && depth < 20) {
      if (currentInsn instanceof MethodInsnNode) {
        MethodInsnNode mi = (MethodInsnNode) currentInsn;
        if (mi.name.equals("uri") || mi.name.equals("path")) {
          String uriPart = extractUriArgument(mi, classFields);
          if (uriPart != null) {
            uriBuilder.insert(0, uriPart);
          }
        }
      } else if (currentInsn instanceof LdcInsnNode) {
        LdcInsnNode ldcInsn = (LdcInsnNode) currentInsn;
        if (ldcInsn.cst instanceof String) {
          uriParams.add((String) ldcInsn.cst);
        }
      }
      currentInsn = currentInsn.getPrevious();
      depth++;
    }
  }

  private void scanNextInstructions(AbstractInsnNode startInsn, StringBuilder uriBuilder,
      Map<String, String> classFields) {
    AbstractInsnNode currentInsn = startInsn.getNext();
    int depth = 0;

    while (currentInsn != null && depth < 20) {
      if (currentInsn instanceof MethodInsnNode) {
        MethodInsnNode mi = (MethodInsnNode) currentInsn;
        if (mi.name.equals("uri") || mi.name.equals("path")) {
          String uriPart = extractUriArgument(mi, classFields);
          if (uriPart != null) {
            uriBuilder.append(uriPart);
          }
        }
      }
      currentInsn = currentInsn.getNext();
      depth++;
    }
  }

  private String extractUriArgument(MethodInsnNode uriMethodInsn, Map<String, String> classFields) {
    AbstractInsnNode currentInsn = uriMethodInsn.getPrevious();
    StringBuilder uriPart = new StringBuilder();

    while (currentInsn != null) {
      if (currentInsn instanceof LdcInsnNode) {
        LdcInsnNode ldcInsn = (LdcInsnNode) currentInsn;
        if (ldcInsn.cst instanceof String) {
          uriPart.insert(0, (String) ldcInsn.cst);
          break;
        }
      } else if (currentInsn instanceof FieldInsnNode) {
        FieldInsnNode fieldInsn = (FieldInsnNode) currentInsn;
        String fieldValue = classFields.get(fieldInsn.name);
        if (fieldValue != null) {
          uriPart.insert(0, fieldValue);
          break;
        }
      } else if (currentInsn instanceof MethodInsnNode) {
        MethodInsnNode methodInsn = (MethodInsnNode) currentInsn;
        if (methodInsn.name.equals("format")) {
          // Handle String.format() calls
          uriPart.insert(0, extractFormatArguments(methodInsn));
          break;
        }
      }
      currentInsn = currentInsn.getPrevious();
    }

    return uriPart.toString();
  }

  private String extractFormatArguments(MethodInsnNode formatMethodInsn) {
    AbstractInsnNode currentInsn = formatMethodInsn.getPrevious();
    List<String> formatArgs = new ArrayList<>();
    String formatString = null;

    while (currentInsn != null) {
      if (currentInsn instanceof LdcInsnNode) {
        LdcInsnNode ldcInsn = (LdcInsnNode) currentInsn;
        if (ldcInsn.cst instanceof String) {
          formatString = (String) ldcInsn.cst;
          break;
        }
      } else if (currentInsn instanceof MethodInsnNode) {
        MethodInsnNode methodInsn = (MethodInsnNode) currentInsn;
        formatArgs.add(0, methodInsn.name + "()");
      }
      currentInsn = currentInsn.getPrevious();
    }

    if (formatString != null) {
      return String.format(formatString, formatArgs.toArray());
    }

    return "";
  }

  private ExternalCallInfo extractExternalCallInfo(ClassNode classNode, MethodNode methodNode,
      MethodInsnNode methodInsn, String baseUrl, Map<String, String> classFields) {
    String endpoint = extractEndpoint(methodNode, methodInsn, classFields);
    String fullUrl = "Unknown";
    List<String> parameters = Collections.emptyList();

    if (endpoint != null) {
      fullUrl = combineUrls(baseUrl, endpoint);
      parameters = extractParameters(endpoint);
    }

    String httpMethod = extractHttpMethod(methodInsn);
    String serviceName = extractServiceName(classNode);
    String purpose = extractPurpose(methodNode);
    String description = extractDescription(methodNode);
    String responseType = extractResponseType(methodNode, methodInsn);

    return new ExternalCallInfo(fullUrl, httpMethod, parameters, purpose, responseType, purpose, "", serviceName, "",
        serviceName, description, "");

  }

  private String combineUrls(String baseUrl, String endpoint) {
    if (baseUrl == null)
      return endpoint;
    baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    endpoint = endpoint.startsWith("/") ? endpoint : "/" + endpoint;
    return baseUrl + endpoint;
  }

  private boolean isHttpClientMethod(MethodInsnNode methodInsn) {
    return methodInsn.owner.contains("WebClient") &&
        (methodInsn.name.equals("get") || methodInsn.name.equals("post") ||
            methodInsn.name.equals("put") || methodInsn.name.equals("delete"));
  }

  private String extractHttpMethod(MethodInsnNode methodInsn) {
    return methodInsn.name.toUpperCase();
  }

  private String extractServiceName(ClassNode classNode) {
    return classNode.name.substring(classNode.name.lastIndexOf('/') + 1).replace("Client", "");
  }

  private String extractPurpose(MethodNode methodNode) {
    return methodNode.name;
  }

  private String extractDescription(MethodNode methodNode) {
    // This could be enhanced to extract JavaDoc comments if available
    return "Method: " + methodNode.name;
  }

  private String extractResponseType(MethodNode methodNode, MethodInsnNode methodInsn) {
    // Look for the bodyToMono or bodyToFlux method call
    AbstractInsnNode currentInsn = methodInsn;
    while (currentInsn != null) {
      if (currentInsn instanceof MethodInsnNode) {
        MethodInsnNode mi = (MethodInsnNode) currentInsn;
        if (mi.name.equals("bodyToMono") || mi.name.equals("bodyToFlux")) {
          // The class type should be the previous instruction
          AbstractInsnNode prevInsn = mi.getPrevious();
          if (prevInsn instanceof LdcInsnNode) {
            LdcInsnNode ldcInsn = (LdcInsnNode) prevInsn;
            if (ldcInsn.cst instanceof Type) {
              return ((Type) ldcInsn.cst).getClassName();
            }
          }
          break;
        }
      }
      currentInsn = currentInsn.getNext();
    }
    // If we couldn't find it, fall back to the method return type
    return Type.getReturnType(methodNode.desc).getClassName();
  }

  private List<String> extractParameters(String endpoint) {
    if (endpoint == null) {
      return Collections.emptyList();
    }
    List<String> parameters = new ArrayList<>();
    Pattern pattern = Pattern.compile("\\{([^}]+)\\}");
    Matcher matcher = pattern.matcher(endpoint);
    while (matcher.find()) {
      parameters.add(matcher.group(1));
    }
    return parameters;
  }

  private String resolvePropertyValue(String key) {
    String value = configProperties.get(key);
    return value != null ? value : "${" + key + "}";
  }
}