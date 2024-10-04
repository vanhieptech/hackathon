package com.example;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.model.APIInfo;
import com.example.model.APIInfo.ExternalAPI;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class APIAnalyzer {
  private static final int MAX_DEPTH = 1000;
  private static final Logger logger = LoggerFactory.getLogger(APIAnalyzer.class);
  private final Map<String, String> configProperties;
  private final String projectName;
  private final List<ClassNode> allClasses;
  private final String basePath;
  private final Set<String> enabledEndpoints;
  private final Set<String> ignoredPaths;
  private final String serviceName;
  private final Map<String, Set<String>> serviceMethodCalls;
  private final Map<String, String> variableAssignments;

  public APIAnalyzer(Map<String, String> configProperties, String projectName, List<ClassNode> allClasses) {
    this.configProperties = configProperties;
    this.projectName = projectName;
    this.allClasses = allClasses;
    this.serviceName = configProperties.getOrDefault("spring.application.name", projectName);
    String port = configProperties.getOrDefault("server.port", "8080");
    this.basePath = "http://" + serviceName + ":" + port;
    this.enabledEndpoints = parseCommaSeparatedConfig("api.enabled-endpoints");
    this.ignoredPaths = parseCommaSeparatedConfig("api.ignored-paths");
    this.serviceMethodCalls = new HashMap<>();
    this.variableAssignments = new HashMap<>();
    buildServiceMethodCallMap();
    logger.info("APIAnalyzer initialized with base path: {}", basePath);
  }

  public APIInfo analyzeAPI() {
    logger.info("Starting API analysis for project: {}", serviceName);
    APIInfo apiInfo = new APIInfo(serviceName, basePath);
    List<APIInfo.ExposedAPI> exposedApis = new ArrayList<>();

    logger.info("Analyzing {} classes", allClasses.size());
    for (ClassNode classNode : allClasses) {
      logger.info("Checking if class {} is an API class", classNode.name);
      if (isApiClass(classNode)) {
        logger.info("Processing API class: {}", classNode.name);
        List<APIInfo.ExposedAPI> apis = extractAPIsFromClass(classNode, allClasses);
        logger.info("Extracted {} APIs from class {}", apis.size(), classNode.name);
        for (APIInfo.ExposedAPI api : apis) {
          logger.info("Building dependency tree for API: {}.{}", classNode.name, api.getServiceMethod());
          List<APIInfo.ExternalAPI> externalAPIs = extractExternalAPICalls(classNode, api.getServiceMethod());
          logger.info("Extracted {} external API calls for {}.{}", externalAPIs.size(), classNode.name,
              api.getServiceMethod());
          api.setExternalApis(externalAPIs);
        }
        exposedApis.addAll(apis);
        logger.info("Total exposed APIs after processing {}: {}", classNode.name, exposedApis.size());
      }
    }

    apiInfo.setExposedApis(exposedApis);
    identifyAsyncApis(apiInfo);

    logger.info("Completed API analysis. Extracted {} APIs", exposedApis.size());
    return apiInfo;
  }

  private Map<String, Set<String>> buildEnhancedDependencyTree(ClassNode classNode, String methodName, int depth,
      Set<String> visitedMethods) {
    logger.debug("Building enhanced dependency tree for {}.{} at depth {}", classNode.name, methodName, depth);
    Map<String, Set<String>> dependencyTree = new HashMap<>();
    String fullMethodName = classNode.name + "." + methodName;
    if (depth > MAX_DEPTH || visitedMethods.contains(fullMethodName)) {
      logger.warn("Max depth reached or circular dependency detected for method: {}", fullMethodName);
      return dependencyTree;
    }
    visitedMethods.add(fullMethodName);

    MethodNode methodNode = findMethodNode(classNode, methodName);
    if (methodNode == null) {
      logger.warn("Method not found: {}", fullMethodName);
      return dependencyTree;
    }

    Set<String> methodCalls = new HashSet<>();
    dependencyTree.put(fullMethodName, methodCalls);

    // Analyze method instructions
    analyzeMethodInstructions(classNode, methodNode, methodCalls, dependencyTree, depth, visitedMethods);

    // Handle injected services
    Set<String> injectedServices = detectInjectedServices(classNode);
    for (String injectedService : injectedServices) {
      ClassNode serviceNode = findClassNode(injectedService);
      if (serviceNode != null) {
        if ((serviceNode.access & Opcodes.ACC_INTERFACE) != 0) {
          handleServiceInterface(serviceNode, methodName, depth, visitedMethods, dependencyTree);
        } else {
          Map<String, Set<String>> serviceDependencyTree = buildEnhancedDependencyTree(serviceNode, methodName,
              depth + 1,
              new HashSet<>(visitedMethods));
          mergeDependencyTrees(dependencyTree, serviceDependencyTree);
        }
      }
    }

    logger.debug("Completed building enhanced dependency tree for {}", fullMethodName);
    return dependencyTree;
  }

  private void handleServiceInterface(ClassNode interfaceNode, String methodName, int depth,
      Set<String> visitedMethods, Map<String, Set<String>> dependencyTree) {
    logger.info("Handling service interface: {}", interfaceNode.name);
    List<ClassNode> implementations = findClassesImplementingInterface(interfaceNode.name);
    for (ClassNode impl : implementations) {
      logger.debug("Analyzing implementation: {}", impl.name);
      MethodNode implMethodNode = findMethodNode(impl, methodName);
      if (implMethodNode != null) {
        Map<String, Set<String>> implDependencyTree = buildEnhancedDependencyTree(impl, methodName, depth + 1,
            new HashSet<>(visitedMethods));
        mergeDependencyTrees(dependencyTree, implDependencyTree);
      }
    }
  }

  private void analyzeMethodInstructions(ClassNode classNode, MethodNode methodNode, Set<String> methodCalls,
      Map<String, Set<String>> dependencyTree, int depth, Set<String> visitedMethods) {
    for (AbstractInsnNode insn : methodNode.instructions) {
      if (insn instanceof MethodInsnNode) {
        MethodInsnNode methodInsn = (MethodInsnNode) insn;
        String calledMethod = methodInsn.owner.replace('/', '.') + "." + methodInsn.name;
        methodCalls.add(calledMethod);

        ClassNode targetClass = findClassNode(methodInsn.owner);
        if (targetClass != null) {
          if ((targetClass.access & Opcodes.ACC_INTERFACE) != 0) {
            handleServiceInterface(targetClass, methodInsn.name, depth, visitedMethods, dependencyTree);
          } else {
            MethodNode targetMethod = findMethodNode(targetClass, methodInsn.name, methodInsn.desc);
            if (targetMethod != null && depth < MAX_DEPTH) {
              Map<String, Set<String>> subTree = buildEnhancedDependencyTree(targetClass, methodInsn.name, depth + 1,
                  new HashSet<>(visitedMethods));
              mergeDependencyTrees(dependencyTree, subTree);
            }
          }
        }
      } else if (insn instanceof InvokeDynamicInsnNode) {
        analyzeLambdaExpression(classNode, methodNode, (InvokeDynamicInsnNode) insn, methodCalls,
            dependencyTree, depth, visitedMethods);
      }
    }
  }

  private void analyzeReactiveChain(ClassNode classNode, MethodNode methodNode,
      Map<String, Set<String>> dependencyTree) {
    // Implement logic to analyze reactive chains (e.g., Mono, Flux operations)
  }

  private List<APIInfo.ExternalAPI> extractExternalAPICalls(ClassNode classNode, String serviceMethod) {
    logger.info("Extracting external API calls for {}.{}", classNode.name, serviceMethod);
    List<APIInfo.ExternalAPI> externalAPIs = new ArrayList<>();
    Map<String, String> classFields = extractClassFields(classNode);

    // Build the method call tree
    Map<String, Set<String>> methodCallTree = new HashMap<>();
    Set<String> visitedMethods = new HashSet<>();
    MethodNode methodNode = findMethodNode(classNode, serviceMethod);
    if (methodNode == null) {
      logger.warn("Method not found: {}.{}", classNode.name, serviceMethod);
      return externalAPIs;
    }
    buildMethodCallTreeRecursive(classNode, methodNode, methodCallTree, visitedMethods, 0);

    // Extract baseUrl from the method call tree
    String baseUrl = extractBaseUrlFromMethodCallTree(methodCallTree, classFields);

    // Analyze the method call tree for external API calls
    analyzeMethodCallTreeForExternalAPIs(methodCallTree, baseUrl, classFields, externalAPIs);

    // Filter out duplicate external APIs
    externalAPIs = filterDuplicateExternalAPIs(externalAPIs);

    logger.info("Extracted {} unique external API calls for {}.{}", externalAPIs.size(), classNode.name, serviceMethod);
    return externalAPIs;
  }

  private List<APIInfo.ExternalAPI> filterDuplicateExternalAPIs(List<APIInfo.ExternalAPI> externalAPIs) {
    Map<String, APIInfo.ExternalAPI> uniqueAPIs = new HashMap<>();
    for (APIInfo.ExternalAPI api : externalAPIs) {
      String key = api.getBaseUrl() + "|" + api.getPath() + "|" + api.getHttpMethod();
      if (!uniqueAPIs.containsKey(key) || api.getPath().length() < uniqueAPIs.get(key).getPath().length()) {
        uniqueAPIs.put(key, api);
      }
    }
    return new ArrayList<>(uniqueAPIs.values());
  }

  private String extractBaseUrlFromMethodCallTree(Map<String, Set<String>> methodCallTree,
      Map<String, String> classFields) {
    for (Map.Entry<String, Set<String>> entry : methodCallTree.entrySet()) {
      String[] parts = entry.getKey().split("\\.");
      if (parts.length < 2)
        continue;

      String className = parts[0];
      String methodName = parts[1];
      ClassNode currentClass = findClassNode(className);
      if (currentClass == null)
        continue;

      MethodNode currentMethod = findMethodNode(currentClass, methodName);
      if (currentMethod == null)
        continue;

      String baseUrl = extractBaseUrlFromMethod(currentClass, currentMethod, classFields);
      if (baseUrl != null) {
        return baseUrl;
      }
    }
    return null;
  }

  private String extractBaseUrlFromMethod(ClassNode classNode, MethodNode methodNode, Map<String, String> classFields) {
    String baseUrl = null;

    // Check for @Value annotation on fields
    baseUrl = extractBaseUrlFromFields(classNode);
    if (baseUrl != null)
      return baseUrl;

    // Check for WebClient creation in constructor
    baseUrl = extractBaseUrlFromConstructor(classNode, classFields);
    if (baseUrl != null)
      return baseUrl;

    // Check for hardcoded URLs
    baseUrl = findHardcodedUrl(classNode);
    if (baseUrl != null)
      return baseUrl;

    for (AbstractInsnNode insn : methodNode.instructions) {
      if (insn instanceof MethodInsnNode) {
        MethodInsnNode methodInsn = (MethodInsnNode) insn;
        if (isHttpClientMethod(methodInsn)) {
          baseUrl = extractBaseUrlFromHttpClientMethod(classNode, methodNode, methodInsn, classFields);
        } else if (isWebClientMethod(methodInsn)) {
          baseUrl = extractBaseUrlFromWebClientMethod(classNode, methodNode, methodInsn, classFields);
        } else if (isUrlOrUriCreationMethod(methodInsn)) {
          baseUrl = extractBaseUrlFromUrlOrUriCreation(methodInsn);
        }
        if (baseUrl != null) {
          return baseUrl;
        }
      } else if (insn instanceof FieldInsnNode) {
        FieldInsnNode fieldInsn = (FieldInsnNode) insn;
        String fieldValue = classFields.get(fieldInsn.name);
        if (fieldValue != null && (fieldValue.startsWith("http://") || fieldValue.startsWith("https://"))) {
          return fieldValue;
        }
      } else if (insn instanceof LdcInsnNode) {
        LdcInsnNode ldcInsn = (LdcInsnNode) insn;
        if (ldcInsn.cst instanceof String) {
          String value = (String) ldcInsn.cst;
          if (value.startsWith("http://") || value.startsWith("https://")) {
            return value;
          }
        }
      }
    }
    return null;
  }

  private boolean isUrlOrUriCreationMethod(MethodInsnNode methodInsn) {
    return methodInsn.owner.equals("java/net/URL") || methodInsn.owner.equals("java/net/URI");
  }

  private String extractBaseUrlFromUrlOrUriCreation(MethodInsnNode methodInsn) {
    AbstractInsnNode prevInsn = methodInsn.getPrevious();
    if (prevInsn instanceof LdcInsnNode) {
      LdcInsnNode ldcInsn = (LdcInsnNode) prevInsn;
      if (ldcInsn.cst instanceof String) {
        String url = (String) ldcInsn.cst;
        if (url.startsWith("http://") || url.startsWith("https://")) {
          return url;
        }
      }
    }
    return null;
  }

  private String extractBaseUrlFromFields(ClassNode classNode) {
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
    return null;
  }

  private String extractBaseUrlFromHttpClientMethod(ClassNode classNode, MethodNode methodNode,
      MethodInsnNode methodInsn, Map<String, String> classFields) {
    // Check previous instructions for URL or URI creation
    AbstractInsnNode currentInsn = methodInsn.getPrevious();
    while (currentInsn != null) {
      if (currentInsn instanceof MethodInsnNode) {
        MethodInsnNode prevMethodInsn = (MethodInsnNode) currentInsn;
        if (prevMethodInsn.owner.equals("java/net/URL") || prevMethodInsn.owner.equals("java/net/URI")) {
          // Found URL or URI creation, check the previous instruction for the URL string
          AbstractInsnNode urlInsn = prevMethodInsn.getPrevious();
          if (urlInsn instanceof LdcInsnNode) {
            LdcInsnNode ldcInsn = (LdcInsnNode) urlInsn;
            if (ldcInsn.cst instanceof String) {
              String url = (String) ldcInsn.cst;
              if (url.startsWith("http://") || url.startsWith("https://")) {
                return url;
              }
            }
          }
        }
      }
      currentInsn = currentInsn.getPrevious();
    }
    return null;
  }

  private String extractBaseUrlFromWebClientMethod(ClassNode classNode, MethodNode methodNode,
      MethodInsnNode methodInsn, Map<String, String> classFields) {
    // Check for WebClient.Builder.baseUrl() calls
    if (methodInsn.name.equals("baseUrl")) {
      // Look for the baseUrl parameter
      AbstractInsnNode currentInsn = methodInsn.getPrevious();
      while (currentInsn != null) {
        if (currentInsn instanceof FieldInsnNode) {
          FieldInsnNode fieldInsn = (FieldInsnNode) currentInsn;
          String fieldName = fieldInsn.name;
          // Check if the field is in classFields or if it's annotated with @Value
          String fieldValue = classFields.get(fieldName);
          if (fieldValue != null) {
            return fieldValue;
          }
          // If not in classFields, check for @Value annotation
          for (FieldNode field : classNode.fields) {
            if (field.name.equals(fieldName) && field.visibleAnnotations != null) {
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
        }
        currentInsn = currentInsn.getPrevious();
      }
    }
    return null;
  }

  private void analyzeMethodCallTreeForExternalAPIs(Map<String, Set<String>> methodCallTree, String baseUrl,
      Map<String, String> classFields, List<APIInfo.ExternalAPI> externalAPIs) {
    for (Map.Entry<String, Set<String>> entry : methodCallTree.entrySet()) {
      String[] parts = entry.getKey().split("\\.");
      if (parts.length < 2)
        continue;

      String className = parts[0];
      String methodName = parts[1];
      ClassNode currentClass = findClassNode(className);
      if (currentClass == null)
        continue;

      MethodNode currentMethod = findMethodNode(currentClass, methodName);
      if (currentMethod == null)
        continue;

      analyzeMethodForExternalCalls(currentClass, currentMethod, baseUrl, classFields, externalAPIs);
    }
  }

  private void extractExternalAPICallsFromTree(ClassNode classNode, String serviceMethod,
      Map<String, Set<String>> dependencyTree, String baseUrl,
      Map<String, String> classFields, List<APIInfo.ExternalAPI> externalAPIs) {
    logger.info("Extracting external API calls from dependency tree for {}.{}", classNode.name, serviceMethod);

    for (Map.Entry<String, Set<String>> entry : dependencyTree.entrySet()) {
      if (entry.getKey().equals("ExternalAPIs")) {
        for (String apiCall : entry.getValue()) {
          APIInfo.ExternalAPI externalAPI = parseExternalAPIFromString(apiCall);
          if (externalAPI != null) {
            externalAPIs.add(externalAPI);
            logger.info("Added external API: {}", externalAPI);
          }
        }
      } else {
        String[] parts = entry.getKey().split("\\.");
        if (parts.length != 2) {
          logger.warn("Invalid dependency tree key: {}", entry.getKey());
          continue;
        }
        String className = parts[0];
        String methodName = parts[1];

        ClassNode currentClass = findClassNode(className);
        if (currentClass == null) {
          logger.warn("Class not found: {}", className);
          continue;
        }
        MethodNode methodNode = findMethodNode(currentClass, methodName);
        if (methodNode == null) {
          logger.warn("Method not found: {}.{}", className, methodName);
          continue;
        }

        analyzeMethodForExternalCalls(currentClass, methodNode, baseUrl, classFields, externalAPIs);
      }
    }
    logger.info("Completed extracting external API calls from dependency tree");
  }

  private void analyzeMethodForExternalCalls(ClassNode classNode, MethodNode methodNode, String baseUrl,
      Map<String, String> classFields, List<APIInfo.ExternalAPI> externalAPIs) {
    for (AbstractInsnNode insn : methodNode.instructions) {
      if (insn instanceof MethodInsnNode) {
        MethodInsnNode methodInsn = (MethodInsnNode) insn;
        if (isHttpClientMethod(methodInsn) || isMessageBrokerMethod(methodInsn)) {
          logger.info("Found external call method: {}.{}", methodInsn.owner, methodInsn.name);
          APIInfo.ExternalAPI externalAPI = extractExternalAPIInfo(classNode, methodNode, methodInsn, baseUrl,
              classFields);
          if (externalAPI != null) {
            externalAPIs.add(externalAPI);
            logger.info("Added external API: {}", externalAPI);
          }
        } else if (isOptionalMapMethod(methodInsn)) {
          analyzeLambdaExpression(classNode, methodNode, methodInsn, baseUrl, classFields, externalAPIs);
        } else {
          // Analyze all other method calls
          ClassNode targetClass = findClassNode(methodInsn.owner);
          if (targetClass != null) {
            MethodNode targetMethod = findMethodNode(targetClass, methodInsn.name, methodInsn.desc);
            if (targetMethod != null) {
              analyzeMethodForExternalCalls(targetClass, targetMethod, baseUrl, classFields, externalAPIs);
            }
          }
        }
      } else if (insn instanceof InvokeDynamicInsnNode) {
        // Handle InvokeDynamic instructions (lambdas)
        analyzeLambdaExpression(classNode, methodNode, (InvokeDynamicInsnNode) insn, baseUrl, classFields,
            externalAPIs);
      }
    }
  }

  private void analyzeLambdaExpression(ClassNode classNode, MethodNode methodNode, MethodInsnNode methodInsn,
      String baseUrl, Map<String, String> classFields, List<APIInfo.ExternalAPI> externalAPIs) {
    // This method handles the case where a lambda is used within an Optional.map()
    // or similar method
    AbstractInsnNode currentInsn = methodInsn.getNext();
    while (currentInsn != null) {
      if (currentInsn instanceof InvokeDynamicInsnNode) {
        InvokeDynamicInsnNode lambdaInsn = (InvokeDynamicInsnNode) currentInsn;
        if (lambdaInsn.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")) {
          analyzeLambdaBody(classNode, lambdaInsn, baseUrl, classFields, externalAPIs);
        }
        break;
      }
      currentInsn = currentInsn.getNext();
    }
  }

  private void analyzeLambdaExpression(ClassNode classNode, MethodNode methodNode, InvokeDynamicInsnNode lambdaInsn,
      String baseUrl, Map<String, String> classFields, List<APIInfo.ExternalAPI> externalAPIs) {
    if (lambdaInsn.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")) {
      analyzeLambdaBody(classNode, lambdaInsn, baseUrl, classFields, externalAPIs);
    }
  }

  private void analyzeLambdaBody(ClassNode classNode, InvokeDynamicInsnNode lambdaInsn,
      String baseUrl, Map<String, String> classFields, List<APIInfo.ExternalAPI> externalAPIs) {
    String lambdaMethod = lambdaInsn.name;
    ClassNode lambdaClass = findClassNode(classNode.name + "$Lambda");
    if (lambdaClass != null) {
      MethodNode lambdaMethodNode = findMethodNode(lambdaClass, lambdaMethod);
      if (lambdaMethodNode != null) {
        analyzeMethodForExternalCalls(lambdaClass, lambdaMethodNode, baseUrl, classFields, externalAPIs);
      }
    } else {
      // Handle anonymous lambda
      for (MethodNode method : classNode.methods) {
        if (method.name.startsWith("lambda$") && method.desc.equals(lambdaInsn.desc)) {
          analyzeMethodForExternalCalls(classNode, method, baseUrl, classFields, externalAPIs);
          break;
        }
      }
    }
  }

  private void analyzeLambdaExpression(ClassNode classNode, MethodNode methodNode, InvokeDynamicInsnNode lambdaInsn,
      Set<String> methodCalls, Map<String, Set<String>> dependencyTree,
      int depth, Set<String> visitedMethods) {
    if (lambdaInsn.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")) {
      String lambdaMethod = lambdaInsn.name;
      ClassNode lambdaClass = findClassNode(classNode.name + "$Lambda");
      if (lambdaClass != null) {
        MethodNode lambdaMethodNode = findMethodNode(lambdaClass, lambdaMethod);
        if (lambdaMethodNode != null) {
          Map<String, Set<String>> lambdaTree = buildEnhancedDependencyTree(lambdaClass, lambdaMethod, depth + 1,
              new HashSet<>(visitedMethods));
          mergeDependencyTrees(dependencyTree, lambdaTree);
        }
      } else {
        // Handle anonymous lambda
        for (MethodNode method : classNode.methods) {
          if (method.name.startsWith("lambda$") && method.desc.equals(lambdaInsn.desc)) {
            Map<String, Set<String>> lambdaTree = buildEnhancedDependencyTree(classNode, method.name, depth + 1,
                new HashSet<>(visitedMethods));
            mergeDependencyTrees(dependencyTree, lambdaTree);
            break;
          }
        }
      }
    }
  }

  private APIInfo.ExternalAPI parseExternalAPIFromString(String apiCall) {
    try {
      String[] parts = apiCall.split("\\|");
      if (parts.length != 7) {
        logger.warn("Invalid external API string format: {}", apiCall);
        return null;
      }

      String targetService = parts[0];
      String fullUrl = parts[1];
      String httpMethod = parts[2];
      boolean isAsync = Boolean.parseBoolean(parts[3]);
      String type = parts[4];
      String responseType = parts[5];
      String callerMethod = parts[6];
      String baseUrl = parts[8];

      return new APIInfo.ExternalAPI(targetService, fullUrl, httpMethod, isAsync, type, responseType, callerMethod,
          baseUrl);
    } catch (Exception e) {
      logger.error("Error parsing external API string: {}", apiCall, e);
      return null;
    }
  }

  private void identifyAsyncApis(APIInfo apiInfo) {
    for (APIInfo.ExposedAPI api : apiInfo.getExposedApis()) {
      boolean isAsync = isAsyncMethod(api) ||
          hasReactiveReturnType(api.getReturnType()) ||
          isMessagingMethod(api);
      api.setAsync(isAsync);
    }
  }

  private void buildMethodCallTree(APIInfo.ExposedAPI exposedAPI, ClassNode classNode, String methodName) {
    logger.debug("Building method call tree for {}.{}", classNode.name, methodName);
    Set<String> visitedMethods = new HashSet<>();
    Map<String, Set<String>> methodCallTree = new HashMap<>();

    MethodNode methodNode = findMethodNode(classNode, methodName);
    if (methodNode == null) {
      logger.warn("Method not found: {}.{}", classNode.name, methodName);
      return;
    }

    buildMethodCallTreeRecursive(classNode, methodNode, methodCallTree, visitedMethods, 0);
    exposedAPI.setMethodCallTree(methodCallTree);

    logger.info("Completed building method call tree for {}.{}. Tree size: {}", classNode.name, methodName,
        methodCallTree.size());
  }

  private Map<String, Set<String>> buildDependencyTree(ClassNode classNode, String methodName, int depth,
      Set<String> visitedMethods) {
    if (depth > MAX_DEPTH || visitedMethods.contains(classNode.name + "." + methodName)) {
      logger.warn("Max depth reached or circular dependency detected for method: {}.{}", classNode.name, methodName);
      return new HashMap<>();
    }
    visitedMethods.add(classNode.name + "." + methodName);

    logger.info("Building dependency tree for {}.{} at depth {}", classNode.name, methodName, depth);
    Map<String, Set<String>> dependencyTree = new HashMap<>();
    Set<String> dependencies = new HashSet<>();
    dependencyTree.put(classNode.name + "." + methodName, dependencies);

    MethodNode methodNode = findMethodNode(classNode, methodName);
    if (methodNode == null) {
      if ((classNode.access & Opcodes.ACC_INTERFACE) != 0) {
        List<ClassNode> implementationClasses = findAllInterfaceImplementations(classNode);
        for (ClassNode implementationClass : implementationClasses) {
          Map<String, Set<String>> implDependencyTree = buildDependencyTree(implementationClass, methodName, depth,
              new HashSet<>(visitedMethods));
          mergeDependencyTrees(dependencyTree, implDependencyTree);
        }
      }
      logger.warn("Method not found: {}.{}", classNode.name, methodName);
      return dependencyTree;
    }

    Set<String> injectedServices = detectInjectedServices(classNode);
    analyzeMethodCallsRecursively(classNode, methodNode, injectedServices, dependencies, dependencyTree, depth,
        visitedMethods);
    analyzeMethodCalls(classNode, methodNode, injectedServices, dependencies,
        dependencyTree, depth, visitedMethods);

    logger.info("Completed dependency tree for {}.{} at depth {}", classNode.name, methodName, depth);
    return dependencyTree;
  }

  private void analyzeMethodCallsRecursively(ClassNode classNode, MethodNode methodNode, Set<String> injectedServices,
      Set<String> analyzedMethods, Map<String, Set<String>> dependencyTree,
      int depth, Set<String> visitedMethods) {
    String methodKey = classNode.name + "." + methodNode.name;
    if (analyzedMethods.contains(methodKey)) {
      return;
    }
    analyzedMethods.add(methodKey);

    Set<String> methodCalls = dependencyTree.computeIfAbsent(methodKey, k -> new HashSet<>());

    for (AbstractInsnNode insn : methodNode.instructions) {
      if (insn instanceof MethodInsnNode) {
        MethodInsnNode methodInsn = (MethodInsnNode) insn;
        String calledMethod = methodInsn.owner.replace('/', '.') + "." + methodInsn.name;
        methodCalls.add(calledMethod);

        ClassNode targetClass = findClassNode(methodInsn.owner);
        if (targetClass != null) {
          MethodNode targetMethod = findMethodNode(targetClass, methodInsn.name, methodInsn.desc);
          if (targetMethod != null) {
            if (depth < MAX_DEPTH) {
              analyzeMethodCallsRecursively(targetClass, targetMethod, injectedServices, analyzedMethods,
                  dependencyTree, depth + 1, visitedMethods);
            }
          }
        }
      } else if (insn instanceof InvokeDynamicInsnNode) {
        // Handle lambda expressions
        analyzeLambdaExpression(classNode, methodNode, (InvokeDynamicInsnNode) insn, injectedServices,
            analyzedMethods, dependencyTree, depth, visitedMethods);
      }
    }
  }

  private List<ClassNode> findAllInterfaceImplementations(ClassNode interfaceNode) {
    return allClasses.stream()
        .filter(cn -> cn.interfaces.contains(interfaceNode.name))
        .collect(Collectors.toList());
  }

  private void analyzeInterfaceMethod(ClassNode interfaceNode, String methodName,
      Map<String, Set<String>> dependencyTree) {
    List<ClassNode> implementations = findAllInterfaceImplementations(interfaceNode);
    for (ClassNode impl : implementations) {
      MethodNode methodNode = findMethodNode(impl, methodName);
      if (methodNode != null) {
        Set<String> injectedServices = detectInjectedServices(impl);
        analyzeMethodCallsRecursively(impl, methodNode, injectedServices, new HashSet<>(), dependencyTree, 0,
            new HashSet<>());
      }
    }
  }

  private void mergeDependencyTrees(Map<String, Set<String>> mainTree, Map<String, Set<String>> subTree) {
    for (Map.Entry<String, Set<String>> entry : subTree.entrySet()) {
      mainTree.computeIfAbsent(entry.getKey(), k -> new HashSet<>()).addAll(entry.getValue());
    }
  }

  private void analyzeMethodCalls(ClassNode classNode, MethodNode methodNode, Set<String> injectedServices,
      Set<String> dependencies, Map<String, Set<String>> dependencyTree, int depth, Set<String> visitedMethods) {
    for (AbstractInsnNode insn : methodNode.instructions) {
      if (insn instanceof MethodInsnNode) {
        MethodInsnNode methodInsn = (MethodInsnNode) insn;
        String calledMethod = methodInsn.owner + "." + methodInsn.name;
        dependencies.add(calledMethod);

        if (injectedServices.contains(methodInsn.owner.replace('/', '.'))) {
          ClassNode targetClass = findClassNode(methodInsn.owner);
          if (targetClass != null) {
            if ((targetClass.access & Opcodes.ACC_INTERFACE) != 0) {
              ClassNode implementationClass = findInterfaceImplementation(targetClass);
              if (implementationClass != null) {
                targetClass = implementationClass;
              }
            }

            if (!visitedMethods.contains(targetClass.name + "." + methodInsn.name)) {
              Map<String, Set<String>> subTree = buildDependencyTree(targetClass, methodInsn.name, depth + 1,
                  new HashSet<>(visitedMethods));
              dependencyTree.putAll(subTree);
              analyzeInjectedServiceMethod(targetClass, methodInsn.name, dependencyTree);
            }
          }
        }

        if (isHttpClientMethod(methodInsn)) {
          logger.info("Found HTTP client method: {}.{}", methodInsn.owner, methodInsn.name);
          APIInfo.ExternalAPI externalAPI = extractExternalAPIInfo(classNode, methodNode, methodInsn, "",
              new HashMap<>());
          if (externalAPI != null) {
            dependencyTree.computeIfAbsent("ExternalAPIs", k -> new HashSet<>()).add(externalAPI.toString());
          }
        }

        if (isMessageBrokerMethod(methodInsn)) {
          logger.info("Found message broker method: {}.{}", methodInsn.owner, methodInsn.name);
          String brokerType = extractBrokerType(methodInsn);
          String topic = extractTopic(methodInsn);
          String returnType = extractReturnType(methodNode);
          String baseUrl = extractBaseUrl(classNode, extractClassFields(classNode));
          APIInfo.ExternalAPI externalAPI = new APIInfo.ExternalAPI(topic, "PUBLISH", methodInsn.name, true, brokerType,
              returnType, classNode.name + "." + methodNode.name, baseUrl);
          dependencyTree.computeIfAbsent("ExternalAPIs", k -> new HashSet<>()).add(externalAPI.toString());
        }

        if (isDatabaseAccessMethod(methodInsn)) {
          logger.info("Found database access method: {}.{}", methodInsn.owner, methodInsn.name);
          String dbCall = classNode.name + "." + methodNode.name + " -> " + methodInsn.owner + "." + methodInsn.name;
          dependencyTree.computeIfAbsent("DatabaseCalls", k -> new HashSet<>()).add(dbCall);
        }

        // Add checks for common Java utility methods and collections operations
        if (isCommonUtilityMethod(methodInsn)) {
          logger.info("Found common utility method: {}.{}", methodInsn.owner, methodInsn.name);
          String utilityCall = classNode.name + "." + methodNode.name + " -> " + methodInsn.owner + "."
              + methodInsn.name;
          dependencyTree.computeIfAbsent("UtilityCalls", k -> new HashSet<>()).add(utilityCall);
        }

        if (isCollectionOperation(methodInsn)) {
          logger.info("Found collection operation: {}.{}", methodInsn.owner, methodInsn.name);
          String collectionOp = classNode.name + "." + methodNode.name + " -> " + methodInsn.owner + "."
              + methodInsn.name;
          dependencyTree.computeIfAbsent("CollectionOperations", k -> new HashSet<>()).add(collectionOp);
        }
      }
    }
  }

  private Set<String> detectInjectedServices(ClassNode classNode) {
    Set<String> injectedServices = new HashSet<>();

    // Check fields for @Autowired, @Inject, or @Resource annotations
    for (FieldNode field : classNode.fields) {
      if (hasAnnotation(field, "Autowired") || hasAnnotation(field, "Inject") || hasAnnotation(field, "Resource")) {
        injectedServices.add(Type.getType(field.desc).getClassName());
      }
    }

    // Check constructor for injected services
    for (MethodNode method : classNode.methods) {
      if (method.name.equals("<init>") && hasAnnotation(method, "Autowired")) {
        Type[] argumentTypes = Type.getArgumentTypes(method.desc);
        for (Type argType : argumentTypes) {
          injectedServices.add(argType.getClassName());
        }
        break;
      }
    }

    return injectedServices;
  }

  private void analyzeInjectedServiceMethod(ClassNode classNode, String methodName,
      Map<String, Set<String>> dependencyTree) {
    MethodNode methodNode = findMethodNode(classNode, methodName);
    if (methodNode == null)
      return;

    for (AbstractInsnNode insn : methodNode.instructions) {
      if (insn instanceof MethodInsnNode) {
        MethodInsnNode methodInsn = (MethodInsnNode) insn;
        if (isHttpClientMethod(methodInsn)) {
          logger.info("Found HTTP client method in injected service: {}.{}", methodInsn.owner, methodInsn.name);
          APIInfo.ExternalAPI externalAPI = extractExternalAPIInfo(classNode, methodNode, methodInsn, "",
              new HashMap<>());
          if (externalAPI != null) {
            dependencyTree.computeIfAbsent("ExternalAPIs", k -> new HashSet<>()).add(externalAPI.toString());
          }
        }
      }
    }
  }

  private boolean isCommonUtilityMethod(MethodInsnNode methodInsn) {
    String owner = methodInsn.owner.replace('/', '.');
    return owner.startsWith("java.util.") ||
        owner.equals("java.lang.") ||
        owner.equals("java.time.");
  }

  private boolean isCollectionOperation(MethodInsnNode methodInsn) {
    String owner = methodInsn.owner.replace('/', '.');
    return (owner.equals("java.util.List") ||
        owner.equals("java.util.Set") ||
        owner.equals("java.util.Map") ||
        owner.equals("java.util.Collection")) &&
        (methodInsn.name.equals("add") ||
            methodInsn.name.equals("remove") ||
            methodInsn.name.equals("contains") ||
            methodInsn.name.equals("size") ||
            methodInsn.name.equals("isEmpty") ||
            methodInsn.name.equals("clear") ||
            methodInsn.name.equals("put") ||
            methodInsn.name.equals("get") ||
            methodInsn.name.equals("containsKey") ||
            methodInsn.name.equals("containsValue"));
  }

  private boolean isDatabaseAccessMethod(MethodInsnNode methodInsn) {
    return methodInsn.owner.contains("Repository") ||
        methodInsn.owner.contains("EntityManager") ||
        methodInsn.owner.contains("JdbcTemplate") ||
        methodInsn.owner.contains("MongoTemplate") ||
        methodInsn.name.toLowerCase().contains("query") ||
        methodInsn.name.toLowerCase().contains("find") ||
        methodInsn.name.toLowerCase().contains("save") ||
        methodInsn.name.toLowerCase().contains("delete");
  }

  private void buildMethodCallTreeRecursive(ClassNode classNode, MethodNode methodNode,
      Map<String, Set<String>> methodCallTree, Set<String> visitedMethods, int depth) {
    if (depth > MAX_DEPTH || visitedMethods.contains(classNode.name + "." + methodNode.name + methodNode.desc)) {
      logger.debug("Max depth reached or circular dependency detected for method: {}.{}", classNode.name,
          methodNode.name);
      return;
    }
    visitedMethods.add(classNode.name + "." + methodNode.name + methodNode.desc);

    logger.debug("Analyzing method calls in {}.{}", classNode.name, methodNode.name);
    Set<String> calledMethods = new HashSet<>();
    methodCallTree.put(classNode.name + "." + methodNode.name, calledMethods);

    for (AbstractInsnNode insn : methodNode.instructions) {
      if (insn instanceof MethodInsnNode) {
        MethodInsnNode methodInsn = (MethodInsnNode) insn;
        String calledMethod = methodInsn.owner.replace('/', '.') + "." + methodInsn.name;
        calledMethods.add(calledMethod);

        ClassNode targetClassNode = findClassNode(methodInsn.owner);
        if (targetClassNode != null) {
          if ((targetClassNode.access & Opcodes.ACC_INTERFACE) != 0) {
            // If the target is an interface, find its implementations
            List<ClassNode> implementations = findClassesImplementingInterface(targetClassNode.name);
            for (ClassNode implNode : implementations) {
              MethodNode implMethodNode = findMethodNode(implNode, methodInsn.name, methodInsn.desc);
              if (implMethodNode != null) {
                logger.debug("Found implementation of interface method: {}.{}", implNode.name, implMethodNode.name);
                buildMethodCallTreeRecursive(implNode, implMethodNode, methodCallTree, new HashSet<>(visitedMethods),
                    depth + 1);
              }
            }
          } else {
            MethodNode targetMethodNode = findMethodNode(targetClassNode, methodInsn.name, methodInsn.desc);
            if (targetMethodNode != null) {
              buildMethodCallTreeRecursive(targetClassNode, targetMethodNode, methodCallTree,
                  new HashSet<>(visitedMethods), depth + 1);
            }
          }
        } else {
          logger.warn("Could not find class for method call: {}.{}", methodInsn.owner, methodInsn.name);
        }
      } else if (insn instanceof InvokeDynamicInsnNode) {
        // Handle lambda expressions
        InvokeDynamicInsnNode invokeDynamicInsn = (InvokeDynamicInsnNode) insn;
        if (invokeDynamicInsn.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")) {
          Handle handle = (Handle) invokeDynamicInsn.bsmArgs[1];
          String lambdaClass = handle.getOwner().replace('/', '.');
          String lambdaMethod = handle.getName();
          calledMethods.add(lambdaClass + "." + lambdaMethod);

          ClassNode lambdaClassNode = findClassNode(handle.getOwner());
          if (lambdaClassNode != null) {
            MethodNode lambdaMethodNode = findMethodNode(lambdaClassNode, lambdaMethod, handle.getDesc());
            if (lambdaMethodNode != null) {
              buildMethodCallTreeRecursive(lambdaClassNode, lambdaMethodNode, methodCallTree,
                  new HashSet<>(visitedMethods), depth + 1);
            }
          }
        }
      }
    }
  }

  private void analyzeLambdaExpression(ClassNode classNode, MethodNode methodNode, InvokeDynamicInsnNode lambdaInsn,
      Set<String> injectedServices, Set<String> analyzedMethods,
      Map<String, Set<String>> dependencyTree, int depth, Set<String> visitedMethods) {
    if (lambdaInsn.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")) {
      String lambdaMethod = lambdaInsn.name;
      ClassNode lambdaClass = findClassNode(classNode.name + "$Lambda");
      if (lambdaClass != null) {
        MethodNode lambdaMethodNode = findMethodNode(lambdaClass, lambdaMethod);
        if (lambdaMethodNode != null) {
          analyzeMethodCallsRecursively(lambdaClass, lambdaMethodNode, injectedServices, analyzedMethods,
              dependencyTree, depth + 1, visitedMethods);
        }
      } else {
        // Handle anonymous lambda
        for (MethodNode method : classNode.methods) {
          if (method.name.startsWith("lambda$") && method.desc.equals(lambdaInsn.desc)) {
            analyzeMethodCallsRecursively(classNode, method, injectedServices, analyzedMethods,
                dependencyTree, depth + 1, visitedMethods);
            break;
          }
        }
      }
    }
  }

  private void analyzeAnonymousLambdaBody(ClassNode classNode, InvokeDynamicInsnNode lambdaInsn,
      Map<String, Set<String>> dependencyTree, Set<String> visitedMethods, int depth) {
    // Extract lambda body instructions
    Handle handle = (Handle) lambdaInsn.bsmArgs[1];
    MethodNode lambdaMethod = findMethodNode(classNode, handle.getName(), handle.getDesc());
    if (lambdaMethod != null) {
      Set<String> injectedServices = detectInjectedServices(classNode);
      analyzeMethodCallsRecursively(classNode, lambdaMethod, injectedServices, new HashSet<>(), dependencyTree,
          depth + 1, new HashSet<>(visitedMethods));
    }
  }

  private boolean hasReactiveReturnType(String returnType) {
    return returnType.contains("Mono") || returnType.contains("Flux");
  }

  private void buildServiceMethodCallMap() {
    for (ClassNode classNode : allClasses) {
      if (isController(classNode)) {
        for (MethodNode methodNode : classNode.methods) {
          Set<String> calledMethods = findCalledServiceMethods(methodNode);
          serviceMethodCalls.put(classNode.name + "." + methodNode.name, calledMethods);
        }
      }
    }
  }

  private Set<String> findCalledServiceMethods(MethodNode methodNode) {
    Set<String> calledMethods = new HashSet<>();
    for (AbstractInsnNode insn : methodNode.instructions) {
      if (insn instanceof MethodInsnNode) {
        MethodInsnNode methodInsn = (MethodInsnNode) insn;
        if (isServiceMethod(methodInsn)) {
          calledMethods.add(methodInsn.owner + "." + methodInsn.name);
        }
      }
    }
    return calledMethods;
  }

  private boolean isServiceMethod(MethodInsnNode methodInsn) {
    ClassNode targetClass = findClassNode(methodInsn.owner);
    return targetClass != null && isService(targetClass);
  }

  private boolean isApiClass(ClassNode classNode, MethodNode methodNode) {
    return isApiClass(classNode) || isValidApiMethod(methodNode) || isGeneratedApiMethod(methodNode);
  }

  private boolean isExternalAPICall(MethodInsnNode methodInsn) {
    return isHttpClientMethod(methodInsn) ||
        isMessageBrokerMethod(methodInsn) ||
        isBackbaseAPICall(methodInsn) ||
        isSQLQuery(methodInsn);
  }

  private boolean isBackbaseAPICall(MethodInsnNode methodInsn) {
    return methodInsn.owner.contains("com/backbase/") &&
        (methodInsn.name.equals("getCapabilities") ||
            methodInsn.name.equals("getEntitlements") ||
            methodInsn.name.equals("getProductSummary"));
  }

  private boolean isSQLQuery(MethodInsnNode methodInsn) {
    return (methodInsn.owner.contains("java/sql/") ||
        methodInsn.owner.contains("javax/persistence/") ||
        methodInsn.owner.contains("org/springframework/jdbc/") ||
        methodInsn.owner.contains("org/hibernate/")) &&
        (methodInsn.name.equals("executeQuery") ||
            methodInsn.name.equals("createQuery") ||
            methodInsn.name.equals("createNativeQuery") ||
            methodInsn.name.equals("query"));
  }

  private void trackVariableAssignment(VarInsnNode varInsn) {
    if (varInsn.getOpcode() == Opcodes.ASTORE) {
      String variableName = "var_" + varInsn.var;
      AbstractInsnNode prevInsn = varInsn.getPrevious();
      if (prevInsn instanceof MethodInsnNode) {
        MethodInsnNode methodInsn = (MethodInsnNode) prevInsn;
        variableAssignments.put(variableName, methodInsn.owner + "." + methodInsn.name);
      }
    }
  }

  private void trackFieldAssignment(FieldInsnNode fieldInsn) {
    if (fieldInsn.getOpcode() == Opcodes.PUTFIELD) {
      String fieldName = fieldInsn.owner + "." + fieldInsn.name;
      AbstractInsnNode prevInsn = fieldInsn.getPrevious();
      if (prevInsn instanceof MethodInsnNode) {
        MethodInsnNode methodInsn = (MethodInsnNode) prevInsn;
        variableAssignments.put(fieldName, methodInsn.owner + "." + methodInsn.name);
      }
    }
  }

  private boolean isApiClass(ClassNode classNode) {
    return isSpringController(classNode) ||
        isQuarkusResource(classNode) ||
        isMicronautController(classNode) ||
        isVertxVerticle(classNode) ||
        isOpenAPIClass(classNode) ||
        isGeneratedApiClass(classNode);
  }

  private boolean isSpringController(ClassNode classNode) {
    return hasAnnotation(classNode, "RestController") ||
        hasAnnotation(classNode, "Controller");
  }

  private boolean isQuarkusResource(ClassNode classNode) {
    return hasAnnotation(classNode, "Path");
  }

  private boolean isMicronautController(ClassNode classNode) {
    return hasAnnotation(classNode, "Controller");
  }

  private boolean isVertxVerticle(ClassNode classNode) {
    return classNode.superName != null && classNode.superName.contains("AbstractVerticle");
  }

  private boolean isOpenAPIClass(ClassNode classNode) {
    return hasAnnotation(classNode, "OpenAPIDefinition") ||
        classNode.name.endsWith("Api") ||
        classNode.name.endsWith("Resource");
  }

  private boolean isGeneratedApiClass(ClassNode classNode) {
    return classNode.visibleAnnotations != null &&
        classNode.visibleAnnotations.stream()
            .anyMatch(an -> an.desc.contains("Generated") ||
                an.desc.contains("ApiModel") ||
                an.desc.contains("ProtoClass"));
  }

  private List<APIInfo.ExposedAPI> extractAPIsFromClass(ClassNode classNode, List<ClassNode> allClasses) {
    List<APIInfo.ExposedAPI> apis = new ArrayList<>();
    String classLevelPath = extractClassLevelPath(classNode);

    for (MethodNode methodNode : classNode.methods) {
      if (isValidApiMethod(methodNode) || isGeneratedApiMethod(methodNode)) {
        APIInfo.ExposedAPI api = extractAPIInfo(classNode, methodNode, classLevelPath);
        if (api != null) {
          buildMethodCallTree(api, classNode, methodNode.name);
          List<APIInfo.ExternalAPI> externalAPIs = extractExternalAPICalls(classNode, methodNode.name);
          api.setExternalApis(externalAPIs);
          api.setGenerated(isGeneratedApiClass(classNode));
          apis.add(api);
        } else {
          logger.info("Skipping invalid API in class: {}, method: {}", classNode.name, methodNode.name);
        }
      }
    }
    return apis;
  }

  private boolean isGeneratedApiMethod(MethodNode methodNode) {
    return methodNode.visibleAnnotations != null &&
        methodNode.visibleAnnotations.stream()
            .anyMatch(an -> an.desc.contains("Generated") ||
                an.desc.contains("ApiOperation") ||
                an.desc.contains("ProtoMethod"));
  }

  private String extractClassLevelPath(ClassNode classNode) {
    if (classNode.visibleAnnotations == null) {
      return "";
    }

    for (AnnotationNode an : classNode.visibleAnnotations) {
      if (an.desc.contains("RequestMapping")) {
        String value = extractAnnotationValue(an, "value");
        return value != null ? value : "";
      }
    }
    return "";
  }

  private APIInfo.ExposedAPI extractAPIInfo(ClassNode classNode, MethodNode methodNode, String classLevelPath) {
    String httpMethod = extractHttpMethod(methodNode);
    String methodPath = extractMethodPath(methodNode);
    String fullPath = combinePaths(classLevelPath, methodPath);
    String serviceMethod = methodNode.name;
    List<APIInfo.ParameterInfo> parameters = extractParameters(methodNode);
    String returnType = extractReturnType(methodNode);
    String controllerClassName = classNode.name;
    String serviceClassName = extractServiceClassName(classNode);
    boolean isAsync = isAsyncMethod(methodNode);
    String serviceName = extractServiceNameFromUrl(combineUrls(basePath, fullPath));
    if (serviceName == null) {
      serviceName = serviceMethod;
    }
    // Validate the extracted API information
    if (isValidAPI(httpMethod, fullPath, serviceMethod, controllerClassName, serviceClassName)) {
      return new APIInfo.ExposedAPI(
          fullPath,
          httpMethod,
          isAsync,
          serviceClassName,
          controllerClassName,
          serviceMethod,
          returnType,
          parameters,
          basePath,
          serviceName);
    } else {
      logger.warn("Invalid API detected: {}.{}", controllerClassName, serviceMethod);
      return null;
    }
  }

  private boolean isValidAPI(String httpMethod, String fullPath, String serviceMethod,
      String controllerClassName, String serviceClassName) {
    // Check if httpMethod is not null or empty
    if (httpMethod == null || httpMethod.isEmpty()) {
      logger.info("Invalid API: HTTP method is null or empty");
      return false;
    }

    // Check if fullPath is not null or empty
    if (fullPath == null || fullPath.isEmpty()) {
      logger.info("Invalid API: Full path is null or empty");
      return false;
    }

    // Check if serviceMethod is not null or empty
    if (serviceMethod == null || serviceMethod.isEmpty()) {
      logger.info("Invalid API: Service method is null or empty");
      return false;
    }

    // Check if controllerClassName is not null or empty
    if (controllerClassName == null || controllerClassName.isEmpty()) {
      logger.info("Invalid API: Controller class name is null or empty");
      return false;
    }

    // Check if serviceClassName is not null or empty
    if (serviceClassName == null || serviceClassName.isEmpty()) {
      logger.info("Invalid API: Service class name is null or empty");
      return false;
    }

    // Add any additional validation rules as needed

    return true;
  }

  private String combinePaths(String... paths) {
    return Arrays.stream(paths)
        .filter(path -> path != null && !path.isEmpty())
        .collect(Collectors.joining("/"))
        .replaceAll("//+", "/");
  }

  private String extractServiceClassName(ClassNode classNode) {
    logger.debug("Extracting service class name for {}", classNode.name);

    // Check for constructor injection
    String constructorInjectedService = findConstructorInjectedService(classNode);
    if (constructorInjectedService != null) {
      logger.info("Found constructor-injected service: {} for class {}", constructorInjectedService, classNode.name);
      return constructorInjectedService;
    }

    // Check for field injection
    String fieldInjectedService = findFieldInjectedService(classNode);
    if (fieldInjectedService != null) {
      logger.info("Found field-injected service: {} for class {}", fieldInjectedService, classNode.name);
      return fieldInjectedService;
    }

    // Check for static initialization
    String staticInitializedService = findStaticInitializedService(classNode);
    if (staticInitializedService != null) {
      logger.info("Found static-initialized service: {} for class {}", staticInitializedService, classNode.name);
      return staticInitializedService;
    }

    // If not found, try to infer from the class name
    String inferredService = inferServiceFromClassName(classNode);
    if (inferredService != null) {
      logger.info("Inferred service: {} for class {}", inferredService, classNode.name);
      return inferredService;
    }

    // If still not found, search for a class with "Service" suffix in the same
    // package
    String packageService = findServiceInSamePackage(classNode);
    if (packageService != null) {
      logger.info("Found service in the same package: {} for class {}", packageService, classNode.name);
      return packageService;
    }

    // If no service class is found, return the original class name
    logger.warn("No service class found for {}. Using the class itself.", classNode.name);
    return classNode.name.replace('/', '.');
  }

  private String findFieldInjectedService(ClassNode classNode) {
    for (FieldNode field : classNode.fields) {
      if (hasAnnotation(field, "Autowired") || hasAnnotation(field, "Inject") || hasAnnotation(field, "Resource")) {
        String serviceName = Type.getType(field.desc).getClassName();
        logger.debug("Found field-injected service: {} in class {}", serviceName, classNode.name);
        return serviceName;
      }
    }
    // If no annotated field found, check for fields ending with "Service"
    for (FieldNode field : classNode.fields) {
      String fieldType = Type.getType(field.desc).getClassName();
      if (fieldType.endsWith("Service")) {
        logger.debug("Found service field: {} in class {}", fieldType, classNode.name);
        return fieldType;
      }
    }
    return null;
  }

  private String findConstructorInjectedService(ClassNode classNode) {
    for (MethodNode method : classNode.methods) {
      if (method.name.equals("<init>")) {
        Type[] argumentTypes = Type.getArgumentTypes(method.desc);
        if (argumentTypes.length > 0) {
          // Check if the first parameter is a service (ends with "Service")
          String serviceName = argumentTypes[0].getClassName();
          if (serviceName.endsWith("Service")) {
            logger.debug("Found constructor-injected service: {} in class {}", serviceName, classNode.name);
            return serviceName;
          }
        }
        // If no service found in the first constructor, check others
        for (Type argType : argumentTypes) {
          String className = argType.getClassName();
          if (className.endsWith("Service")) {
            logger.debug("Found constructor-injected service: {} in class {}", className, classNode.name);
            return className;
          }
        }
      }
    }
    return null;
  }

  private String findStaticInitializedService(ClassNode classNode) {
    for (MethodNode method : classNode.methods) {
      if (method.name.equals("<clinit>")) {
        for (AbstractInsnNode insn : method.instructions) {
          if (insn instanceof FieldInsnNode && insn.getOpcode() == Opcodes.PUTSTATIC) {
            FieldInsnNode fieldInsn = (FieldInsnNode) insn;
            String serviceName = Type.getType(fieldInsn.desc).getClassName();
            logger.debug("Found static-initialized service: {} in class {}", serviceName, classNode.name);
            return serviceName;
          }
        }
      }
    }
    return null;
  }

  private String inferServiceFromClassName(ClassNode classNode) {
    String className = classNode.name.substring(classNode.name.lastIndexOf('/') + 1);
    if (className.endsWith("Controller")) {
      String serviceName = className.substring(0, className.length() - "Controller".length()) + "Service";
      ClassNode serviceNode = findClassNode(serviceName);
      if (serviceNode != null) {
        logger.debug("Inferred service class: {} for controller {}", serviceName, classNode.name);
        return serviceNode.name.replace('/', '.');
      }
    }
    return null;
  }

  private String findServiceInSamePackage(ClassNode classNode) {
    String packageName = classNode.name.substring(0, classNode.name.lastIndexOf('/') + 1);
    for (ClassNode cn : allClasses) {
      if (cn.name.startsWith(packageName) && cn.name.endsWith("Service")) {
        logger.debug("Found service in the same package: {} for class {}", cn.name, classNode.name);
        return cn.name.replace('/', '.');
      }
    }
    return null;
  }

  private boolean hasAnnotation(ClassNode classNode, String annotationName) {
    return classNode.visibleAnnotations != null &&
        classNode.visibleAnnotations.stream()
            .anyMatch(an -> an.desc.contains(annotationName));
  }

  private boolean hasAnnotation(MethodNode methodNode, String annotationName) {
    return methodNode.visibleAnnotations != null &&
        methodNode.visibleAnnotations.stream()
            .anyMatch(an -> an.desc.contains(annotationName));
  }

  private boolean hasAnnotation(FieldNode field, String annotationName) {
    if (field.visibleAnnotations != null) {
      for (AnnotationNode annotation : field.visibleAnnotations) {
        if (annotation.desc.contains(annotationName)) {
          return true;
        }
      }
    }
    return false;
  }

  private boolean hasAnnotation(String className, String methodName, String annotationName) {
    ClassNode classNode = findClassNode(className);
    if (classNode != null) {
      for (MethodNode methodNode : classNode.methods) {
        if (methodNode.name.equals(methodName)) {
          return methodNode.visibleAnnotations != null &&
              methodNode.visibleAnnotations.stream()
                  .anyMatch(an -> an.desc.contains(annotationName));
        }
      }
    }
    return false;
  }

  private boolean isAsyncMethod(APIInfo.ExposedAPI api) {
    return api.isAsync() || // Check if it's already marked as async
        api.getServiceMethod().contains("Async") || // Check method name
        api.getReturnType().contains("CompletableFuture") ||
        api.getReturnType().contains("Mono") ||
        api.getReturnType().contains("Flux") ||
        isMessagingMethod(api);
  }

  private boolean isMessagingMethod(APIInfo.ExposedAPI api) {
    // Check for common messaging annotations in the method name or class name
    return api.getServiceMethod().contains("RabbitListener") ||
        api.getServiceMethod().contains("KafkaListener") ||
        api.getServiceMethod().contains("JmsListener") ||
        api.getControllerClassName().contains("Messaging");
  }

  private boolean isAsyncMethod(MethodNode methodNode) {
    return hasAnnotation(methodNode, "Async") ||
        methodNode.desc.contains("CompletableFuture") ||
        methodNode.desc.contains("Mono") ||
        methodNode.desc.contains("Flux") ||
        isMessagingMethod(methodNode);
  }

  private boolean isMessagingMethod(MethodNode methodNode) {
    return hasAnnotation(methodNode, "RabbitListener") ||
        hasAnnotation(methodNode, "KafkaListener") ||
        hasAnnotation(methodNode, "JmsListener");
  }

  List<APIInfo.ParameterInfo> extractParameters(MethodNode methodNode) {
    if (methodNode.desc == null) {
      return Collections.emptyList();
    }

    Type[] argumentTypes = Type.getArgumentTypes(methodNode.desc);
    List<APIInfo.ParameterInfo> parameters = new ArrayList<>();

    // Try to get parameter names from debug information
    String[] parameterNames = extractParameterNames(methodNode);

    for (int i = 0; i < argumentTypes.length; i++) {
      String paramType = argumentTypes[i].getClassName();
      String paramName = (parameterNames != null && i < parameterNames.length) ? parameterNames[i] : "arg" + i;
      String annotationType = extractParameterAnnotationType(methodNode, i);
      parameters.add(new APIInfo.ParameterInfo(paramType, paramName, annotationType));
    }

    return parameters;
  }

  private String extractParameterAnnotationType(MethodNode methodNode, int index) {
    if (methodNode.visibleParameterAnnotations != null &&
        index < methodNode.visibleParameterAnnotations.length &&
        methodNode.visibleParameterAnnotations[index] != null) {
      for (AnnotationNode an : methodNode.visibleParameterAnnotations[index]) {
        if (an.desc.contains("PathVariable"))
          return "PathVariable";
        if (an.desc.contains("RequestParam"))
          return "RequestParam";
        if (an.desc.contains("RequestBody"))
          return "RequestBody";
        if (an.desc.contains("RequestPart"))
          return "RequestPart";
        if (an.desc.contains("RequestHeader"))
          return "RequestHeader";
        if (an.desc.contains("CookieValue"))
          return "CookieValue";
        if (an.desc.contains("MatrixVariable"))
          return "MatrixVariable";

        // Add more annotation checks as needed
      }
    }
    return null;
  }

  private String[] extractParameterNames(MethodNode methodNode) {
    Type[] argumentTypes = Type.getArgumentTypes(methodNode.desc);
    String[] parameterNames = new String[argumentTypes.length];

    // Check for parameter annotations
    if (methodNode.visibleParameterAnnotations != null) {
      for (int i = 0; i < methodNode.visibleParameterAnnotations.length; i++) {
        List<AnnotationNode> annotations = methodNode.visibleParameterAnnotations[i];
        if (annotations != null) {
          for (AnnotationNode an : annotations) {
            if (an.desc.contains("RequestParam") || an.desc.contains("PathVariable")
                || an.desc.contains("RequestBody")) {
              if (an.values != null) {
                for (int j = 0; j < an.values.size(); j += 2) {
                  if (an.values.get(j).equals("value") || an.values.get(j).equals("name")) {
                    parameterNames[i] = (String) an.values.get(j + 1);
                    break;
                  }
                }
              }
              // If no name is specified in the annotation, use the parameter type
              if (parameterNames[i] == null) {
                parameterNames[i] = argumentTypes[i].getClassName();
              }
              break;
            }
          }
        }
      }
    }

    // Fall back to local variable names if available
    if (methodNode.localVariables != null) {
      for (LocalVariableNode lvn : methodNode.localVariables) {
        if (lvn.index > 0 && lvn.index <= parameterNames.length) {
          if (parameterNames[lvn.index - 1] == null) {
            parameterNames[lvn.index - 1] = lvn.name;
          }
        }
      }
    }

    // If still null, use generic parameter names
    for (int i = 0; i < parameterNames.length; i++) {
      if (parameterNames[i] == null) {
        parameterNames[i] = "param" + (i + 1);
      }
    }

    return parameterNames;
  }

  private String extractReturnType(MethodNode methodNode) {
    Type returnType = Type.getReturnType(methodNode.desc);
    String typeName = returnType.getClassName();

    if (typeName.contains("ResponseEntity") || typeName.contains("Mono") || typeName.contains("Flux")) {
      return extractGenericType(methodNode, typeName);
    }

    return typeName;
  }

  private List<APIInfo.ExternalAPI> extractMessageBrokerCalls(String controllerClassName, String serviceMethod,
      List<ClassNode> allClasses) {
    List<APIInfo.ExternalAPI> externalAPIs = new ArrayList<>();
    ClassNode controllerClass = findClassNode(controllerClassName, allClasses);
    if (controllerClass == null)
      return externalAPIs;

    MethodNode methodNode = findMethodNode(controllerClass, serviceMethod);
    if (methodNode == null)
      return externalAPIs;

    for (AbstractInsnNode insn : methodNode.instructions) {
      if (insn instanceof MethodInsnNode) {
        MethodInsnNode methodInsn = (MethodInsnNode) insn;
        if (isMessageBrokerMethod(methodInsn)) {
          String brokerType = extractBrokerType(methodInsn);
          String topic = extractTopic(methodInsn);
          String returnType = extractReturnType(methodNode);
          String baseUrl = extractBaseUrl(controllerClass, extractClassFields(controllerClass));
          externalAPIs.add(new APIInfo.ExternalAPI(topic, "PUBLISH", methodInsn.name, true, brokerType,
              returnType, serviceMethod, baseUrl));
        }
      }
    }
    return externalAPIs;
  }

  private boolean isMessageBrokerMethod(MethodInsnNode methodInsn) {
    return isKafkaMethod(methodInsn) ||
        isRabbitMQMethod(methodInsn) ||
        isJmsMethod(methodInsn);
  }

  private boolean isKafkaMethod(MethodInsnNode methodInsn) {
    return methodInsn.owner.contains("KafkaTemplate") &&
        (methodInsn.name.equals("send") ||
            methodInsn.name.equals("sendDefault"));
  }

  private boolean isRabbitMQMethod(MethodInsnNode methodInsn) {
    return methodInsn.owner.contains("RabbitTemplate") &&
        (methodInsn.name.equals("convertAndSend") ||
            methodInsn.name.equals("send"));
  }

  private boolean isJmsMethod(MethodInsnNode methodInsn) {
    return methodInsn.owner.contains("JmsTemplate") &&
        (methodInsn.name.equals("convertAndSend") ||
            methodInsn.name.equals("send"));
  }

  private String extractBrokerType(MethodInsnNode methodInsn) {
    if (methodInsn.owner.contains("KafkaTemplate"))
      return "Kafka";
    if (methodInsn.owner.contains("RabbitTemplate"))
      return "RabbitMQ";
    if (methodInsn.owner.contains("JmsTemplate"))
      return "JMS";
    return "Unknown";
  }

  private String extractTopic(MethodInsnNode methodInsn) {
    for (AbstractInsnNode insn = methodInsn.getPrevious(); insn != null; insn = insn.getPrevious()) {
      if (insn instanceof LdcInsnNode) {
        LdcInsnNode ldcInsn = (LdcInsnNode) insn;
        if (ldcInsn.cst instanceof String) {
          return (String) ldcInsn.cst;
        }
      }
    }
    return "Unknown";
  }

  private String extractTargetService(MethodInsnNode methodInsn, String baseUrl) {
    // Try to extract service name from baseUrl
    if (baseUrl != null && !baseUrl.isEmpty()) {
      String serviceName = extractServiceNameFromUrl(baseUrl);
      if (serviceName != null) {
        return serviceName;
      }
    }

    // If not found in baseUrl, try to extract from FeignClient annotation
    ClassNode targetClass = findClassNode(methodInsn.owner);
    if (targetClass != null && targetClass.visibleAnnotations != null) {
      for (AnnotationNode an : targetClass.visibleAnnotations) {
        if (an.desc.contains("FeignClient")) {
          String feignClientName = extractAnnotationValue(an, "name");
          if (feignClientName != null && !feignClientName.isEmpty()) {
            return feignClientName;
          }
        }
      }
    }

    // If still not found, use the class name
    String className = methodInsn.owner.substring(methodInsn.owner.lastIndexOf('/') + 1);
    if (className.toLowerCase().contains("service")) {
      return className;
    }

    // If all else fails, return "UnknownService"
    return "UnknownService";
  }

  private String extractServiceNameFromUrl(String baseUrl) {
    try {
      URL url = new URL(baseUrl);
      String host = url.getHost();
      String[] parts = host.split("\\.");
      if (parts.length > 0) {
        return parts[0];
      }
    } catch (MalformedURLException e) {
      logger.warn("Failed to parse baseUrl: {}", baseUrl);
    }
    return null;
  }

  private ClassNode findClassNode(String controllerClassName, List<ClassNode> allClasses) {
    return allClasses.stream()
        .filter(cn -> cn.name.equals(controllerClassName))
        .findFirst()
        .orElse(null);
  }

  private MethodNode findMethodNode(ClassNode classNode, String methodName, String methodDesc) {
    return classNode.methods.stream()
        .filter(mn -> mn.name.equals(methodName) && (methodDesc == null || mn.desc.equals(methodDesc)))
        .findFirst()
        .orElse(null);
  }

  private MethodNode findMethodNode(ClassNode classNode, String serviceMethod) {
    return classNode.methods.stream()
        .filter(mn -> mn.name.equals(serviceMethod))
        .findFirst()
        .orElse(null);
  }

  private List<ClassNode> findClassesImplementingInterface(String interfaceName) {
    logger.debug("Finding classes implementing interface: {}", interfaceName);
    List<ClassNode> implementingClasses = new ArrayList<>();
    for (ClassNode classNode : allClasses) {
      if (classNode.interfaces.contains(interfaceName)) {
        implementingClasses.add(classNode);
        logger.debug("Found implementing class: {}", classNode.name);
      }
    }
    return implementingClasses;
  }

  private boolean isOptionalMapMethod(MethodInsnNode methodInsn) {
    return methodInsn.owner.equals("java/util/Optional") && methodInsn.name.equals("map");
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

  private boolean isHttpCMethod(MethodInsnNode methodInsn) {
    return isRestTemplateMethod(methodInsn) ||
        isWebClientMethod(methodInsn) ||
        isFeignClientMethod(methodInsn) ||
        isHttpClientMethod(methodInsn) ||
        isOkHttpClientMethod(methodInsn) ||
        isApacheHttpClientMethod(methodInsn);
  }

  private boolean isRestTemplateMethod(MethodInsnNode methodInsn) {
    return methodInsn.owner.contains("RestTemplate") &&
        (methodInsn.name.equals("exchange") ||
            methodInsn.name.equals("getForObject") ||
            methodInsn.name.equals("getForEntity") ||
            methodInsn.name.equals("postForObject") ||
            methodInsn.name.equals("postForEntity") ||
            methodInsn.name.equals("put") ||
            methodInsn.name.equals("delete"));
  }

  private boolean isWebClientMethod(MethodInsnNode methodInsn) {
    return methodInsn.owner.contains("WebClient") &&
        (methodInsn.name.equals("get") ||
            methodInsn.name.equals("post") ||
            methodInsn.name.equals("put") ||
            methodInsn.name.equals("delete") ||
            methodInsn.name.equals("patch") ||
            methodInsn.name.equals("method"));
  }

  private boolean isFeignClientMethod(MethodInsnNode methodInsn) {
    ClassNode ownerClass = findClassNode(methodInsn.owner);
    return ownerClass != null && hasFeignClientAnnotation(ownerClass);
  }

  private boolean isHttpClientMethod(MethodInsnNode methodInsn) {
    return isRestTemplateMethod(methodInsn) ||
        isWebClientMethod(methodInsn) ||
        isFeignClientMethod(methodInsn) ||
        isHttpURLConnectionMethod(methodInsn) ||
        isOkHttpClientMethod(methodInsn) ||
        isApacheHttpClientMethod(methodInsn) ||
        isJerseyClientMethod(methodInsn) ||
        isRetrofitMethod(methodInsn);
  }

  private boolean isHttpURLConnectionMethod(MethodInsnNode methodInsn) {
    return methodInsn.owner.contains("java/net/HttpURLConnection") &&
        (methodInsn.name.equals("setRequestMethod") ||
            methodInsn.name.equals("getOutputStream") ||
            methodInsn.name.equals("getInputStream"));
  }

  private boolean isOkHttpClientMethod(MethodInsnNode methodInsn) {
    return methodInsn.owner.contains("okhttp3/OkHttpClient") &&
        (methodInsn.name.equals("newCall") ||
            methodInsn.name.equals("execute"));
  }

  private boolean isApacheHttpClientMethod(MethodInsnNode methodInsn) {
    return (methodInsn.owner.contains("org/apache/http/client/HttpClient") ||
        methodInsn.owner.contains("org/apache/http/impl/client/CloseableHttpClient")) &&
        (methodInsn.name.equals("execute") ||
            methodInsn.name.equals("doExecute"));
  }

  private boolean isJerseyClientMethod(MethodInsnNode methodInsn) {
    return methodInsn.owner.contains("javax/ws/rs/client/Client") &&
        (methodInsn.name.equals("target") ||
            methodInsn.name.equals("request") ||
            methodInsn.name.equals("get") ||
            methodInsn.name.equals("post") ||
            methodInsn.name.equals("put") ||
            methodInsn.name.equals("delete"));
  }

  private boolean isRetrofitMethod(MethodInsnNode methodInsn) {
    return methodInsn.owner.contains("retrofit2/Retrofit") &&
        methodInsn.name.equals("create");
  }

  private boolean hasFeignClientAnnotation(ClassNode classNode) {
    return classNode.visibleAnnotations != null &&
        classNode.visibleAnnotations.stream()
            .anyMatch(an -> an.desc.contains("FeignClient"));
  }

  private String extractBaseUrl(ClassNode classNode, Map<String, String> classFields) {
    String baseUrl = null;

    // Check for @Value annotation on fields
    baseUrl = extractBaseUrlFromFields(classNode);
    if (baseUrl != null)
      return baseUrl;

    // Check for @Value annotation on constructor parameters
    baseUrl = extractBaseUrlFromConstructorParams(classNode);
    if (baseUrl != null)
      return baseUrl;

    // Check for hardcoded base URL in constructor
    baseUrl = extractHardcodedBaseUrl(classNode);
    if (baseUrl != null)
      return baseUrl;

    // Check for base URL in class fields
    baseUrl = classFields.get("baseUrl");
    if (baseUrl != null)
      return baseUrl;

    // Check for common field names that might contain the base URL
    String[] commonFieldNames = { "apiUrl", "serviceUrl", "hostUrl", "endpoint" };
    for (String fieldName : commonFieldNames) {
      baseUrl = classFields.get(fieldName);
      if (baseUrl != null)
        return baseUrl;
    }

    // Check for methods that might return the base URL
    baseUrl = extractBaseUrlFromMethods(classNode);
    if (baseUrl != null)
      return baseUrl;

    // If no base URL is found, return a default value or null
    return "http://localhost:8080"; // Default value, change as needed
  }

  private String extractBaseUrlFromConstructor(ClassNode classNode, Map<String, String> classFields) {
    for (MethodNode method : classNode.methods) {
      if (method.name.equals("<init>")) {
        for (AbstractInsnNode insn : method.instructions) {
          if (insn instanceof MethodInsnNode) {
            MethodInsnNode methodInsn = (MethodInsnNode) insn;
            if (isWebClientBuilderMethod(methodInsn)) {
              String baseUrl = extractBaseUrlFromWebClientMethod(classNode, method, methodInsn, classFields);
              if (baseUrl != null)
                return baseUrl;
            }
          }
        }
      }
    }
    return null;
  }

  private boolean isWebClientBuilderMethod(MethodInsnNode methodInsn) {
    return methodInsn.owner.contains("WebClient$Builder") && methodInsn.name.equals("baseUrl");
  }

  private String extractBaseUrlFromConstructorParams(ClassNode classNode) {
    for (MethodNode method : classNode.methods) {
      if (method.name.equals("<init>") && method.visibleParameterAnnotations != null) {
        for (List<AnnotationNode> annotations : method.visibleParameterAnnotations) {
          if (annotations != null) {
            for (AnnotationNode annotation : annotations) {
              if (annotation.desc.contains("Value")) {
                String propertyKey = extractPropertyKeyFromAnnotation(annotation);
                if (propertyKey != null) {
                  return resolvePropertyValue(propertyKey);
                }
              }
            }
          }
        }
      }
    }
    return null;
  }

  private String extractHardcodedBaseUrl(ClassNode classNode) {
    for (MethodNode method : classNode.methods) {
      if (method.name.equals("<init>")) {
        for (AbstractInsnNode insn : method.instructions) {
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
    return null;
  }

  private String extractBaseUrlFromMethods(ClassNode classNode) {
    String[] methodNames = { "getBaseUrl", "getApiUrl", "getServiceUrl", "getHostUrl" };
    for (MethodNode method : classNode.methods) {
      if (Arrays.asList(methodNames).contains(method.name)) {
        // Analyze method body to extract return value
        // This is a simplified example and may need more complex analysis
        for (AbstractInsnNode insn : method.instructions) {
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
    return null;
  }

  private String extractPropertyKeyFromAnnotation(AnnotationNode annotation) {
    List<Object> values = annotation.values;
    if (values != null && values.size() >= 2 && values.get(1) instanceof String) {
      String propertyKey = (String) values.get(1);
      return propertyKey.replaceAll("[\\$\\{\\}]", "");
    }
    return null;
  }

  private String resolvePropertyValue(String key) {
    String value = configProperties.get(key);
    if (value != null) {
      // Resolve nested placeholders
      Pattern pattern = Pattern.compile("\\$\\{([^}]+)\\}");
      Matcher matcher = pattern.matcher(value);
      StringBuffer sb = new StringBuffer();
      while (matcher.find()) {
        String nestedKey = matcher.group(1);
        String nestedValue = configProperties.get(nestedKey);
        matcher.appendReplacement(sb, nestedValue != null ? nestedValue : matcher.group());
      }
      matcher.appendTail(sb);
      return sb.toString();
    }
    return key; // Return the key if no value is found
  }

  private String extractGenericType(MethodNode methodNode, String typeName) {
    if (methodNode.signature != null) {
      Type[] genericTypes = Type.getArgumentTypes(methodNode.signature);
      if (genericTypes != null && genericTypes.length > 0) {
        return typeName + "<" + genericTypes[0].getClassName() + ">";
      }
    }
    return typeName;
  }

  private String findHardcodedUrl(ClassNode classNode) {
    for (MethodNode method : classNode.methods) {
      if (method.name.equals("<init>")) {
        for (AbstractInsnNode insn : method.instructions) {
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
    return null;
  }

  private boolean isValidApiMethod(MethodNode methodNode) {
    // Exclude constructor methods
    if (methodNode.name.equals("<init>")) {
      return false;
    }

    // Exclude static initializer methods
    if (methodNode.name.equals("<clinit>")) {
      return false;
    }

    // Exclude private methods
    if ((methodNode.access & Opcodes.ACC_PRIVATE) != 0) {
      return false;
    }

    // Include only methods with API-related annotations
    return hasApiAnnotation(methodNode);
  }

  private boolean hasApiAnnotation(MethodNode methodNode) {
    if (methodNode.visibleAnnotations == null) {
      return false;
    }

    for (AnnotationNode annotation : methodNode.visibleAnnotations) {
      String annotationName = Type.getType(annotation.desc).getClassName();
      if (annotationName.endsWith("Mapping") || annotationName.endsWith("GetMapping") ||
          annotationName.endsWith("PostMapping") || annotationName.endsWith("PutMapping") ||
          annotationName.endsWith("DeleteMapping") || annotationName.endsWith("PatchMapping")) {
        return true;
      }
    }

    return false;
  }

  private APIInfo.ExternalAPI extractExternalAPIInfo(ClassNode classNode, MethodNode methodNode,
      MethodInsnNode methodInsn, String baseUrl,
      Map<String, String> classFields) {
    logger.info("Extracting external API info for {}.{}", classNode.name, methodNode.name);
    String endpoint = extractEndpoint(methodNode, methodInsn, classFields);
    logger.info("Extracted endpoint: {}", endpoint);
    String path = endpoint;
    logger.info("Combined full URL: {}", path);
    String httpMethod = extractHttpMethod(methodInsn);
    logger.info("Extracted HTTP method: {}", httpMethod);
    String targetService = extractTargetService(methodInsn, baseUrl);
    logger.info("Extracted target service: {}", targetService);
    boolean isAsync = isAsyncCall(methodInsn);
    logger.info("Is async call: {}", isAsync);
    String responseType = extractResponseType(methodNode, methodInsn);
    logger.info("Extracted response type: {}", responseType);

    APIInfo.ExternalAPI externalAPI = new APIInfo.ExternalAPI(targetService, path, httpMethod, isAsync, "HTTP",
        responseType, classNode.name + "." + methodNode.name, baseUrl);
    logger.info("Created external API info: {}", externalAPI);
    return externalAPI;
  }

  private ClassNode findInterfaceImplementation(ClassNode interfaceNode) {
    for (ClassNode classNode : allClasses) {
      if (classNode.interfaces.contains(interfaceNode.name)) {
        return classNode;
      }
    }
    return null;
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
      logger.info("Extracted endpoint: {}", endpoint);
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

  private String extractMethodPath(MethodNode methodNode) {
    return extractPathFromAnnotation(methodNode.visibleAnnotations, "Mapping");
  }

  private String extractPathFromAnnotation(List<AnnotationNode> annotations, String annotationName) {
    if (annotations == null)
      return "";
    for (AnnotationNode an : annotations) {
      if (an.desc.contains(annotationName)) {
        return extractPathValue(an);
      }
    }
    return "";
  }

  private String extractPathValue(AnnotationNode an) {
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
    return "";
  }

  private String extractHttpMethod(MethodNode methodNode) {
    if (methodNode.visibleAnnotations != null) {
      for (AnnotationNode an : methodNode.visibleAnnotations) {
        String desc = an.desc.toLowerCase();
        if (desc.contains("mapping")) {
          if (desc.contains("get"))
            return "GET";
          if (desc.contains("post"))
            return "POST";
          if (desc.contains("put"))
            return "PUT";
          if (desc.contains("delete"))
            return "DELETE";
          if (desc.contains("patch"))
            return "PATCH";
          if (desc.contains("head"))
            return "HEAD";
          if (desc.contains("options"))
            return "OPTIONS";
          if (desc.contains("trace"))
            return "TRACE";

          // Handle @RequestMapping
          if (desc.contains("requestmapping")) {
            return extractMethodFromRequestMapping(an);
          }
        }
      }
    }
    return "UNKNOWN";
  }

  private String extractMethodFromRequestMapping(AnnotationNode an) {
    if (an.values != null) {
      for (int i = 0; i < an.values.size(); i += 2) {
        if (an.values.get(i).equals("method")) {
          Object methodValue = an.values.get(i + 1);
          if (methodValue instanceof String[]) {
            String[] methods = (String[]) methodValue;
            if (methods.length > 0) {
              String fullMethodName = methods[0];
              String[] parts = fullMethodName.split("\\.");
              return parts[parts.length - 1].toUpperCase();
            }
          } else if (methodValue instanceof List) {
            List<String[]> methods = (List<String[]>) methodValue;
            if (!methods.isEmpty()) {
              return methods.get(0)[1].toUpperCase();
            }
          }
        }
      }
    }
    return "GET"; // Default to GET if method is not specified
  }

  private String extractHttpMethod(MethodInsnNode methodInsn) {
    if (isRestTemplateMethod(methodInsn)) {
      return extractRestTemplateHttpMethod(methodInsn);
    } else if (isWebClientMethod(methodInsn)) {
      return methodInsn.name.toUpperCase();
    } else if (isFeignClientMethod(methodInsn)) {
      return extractFeignClientHttpMethod(methodInsn);
    } else {
      return "UNKNOWN";
    }
  }

  private String extractRestTemplateHttpMethod(MethodInsnNode methodInsn) {
    switch (methodInsn.name) {
      case "getForObject":
      case "getForEntity":
        return "GET";
      case "postForObject":
      case "postForEntity":
        return "POST";
      case "put":
        return "PUT";
      case "delete":
        return "DELETE";
      case "exchange":
        return extractHttpMethodFromExchange(methodInsn);
      default:
        return "UNKNOWN";
    }
  }

  private String extractHttpMethodFromExchange(MethodInsnNode methodInsn) {
    AbstractInsnNode currentNode = methodInsn.getPrevious();
    while (currentNode != null) {
      if (currentNode instanceof FieldInsnNode) {
        FieldInsnNode fieldInsn = (FieldInsnNode) currentNode;
        if (fieldInsn.owner.contains("HttpMethod")) {
          return fieldInsn.name;
        }
      }
      currentNode = currentNode.getPrevious();
    }
    return "UNKNOWN";
  }

  private String extractFeignClientHttpMethod(MethodInsnNode methodInsn) {
    ClassNode targetClass = findClassNode(methodInsn.owner);
    if (targetClass != null) {
      for (MethodNode method : targetClass.methods) {
        if (method.name.equals(methodInsn.name) && method.desc.equals(methodInsn.desc)) {
          if (method.visibleAnnotations != null) {
            for (AnnotationNode an : method.visibleAnnotations) {
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
              if (an.desc.contains("RequestMapping")) {
                return extractHttpMethodFromRequestMapping(an);
              }
            }
          }
          break;
        }
      }
    }
    return "UNKNOWN";
  }

  private String extractHttpMethodFromRequestMapping(AnnotationNode an) {
    if (an.values != null) {
      for (int i = 0; i < an.values.size(); i += 2) {
        if (an.values.get(i).equals("method")) {
          Object value = an.values.get(i + 1);
          if (value instanceof String[]) {
            String[] methods = (String[]) value;
            if (methods.length > 0) {
              return methods[0].replace("org.springframework.web.bind.annotation.RequestMethod.", "");
            }
          }
        }
      }
    }
    return "GET"; // Default to GET if method is not specified
  }

  private boolean isAsyncCall(MethodInsnNode methodInsn) {
    return methodInsn.desc.contains("Mono") || methodInsn.desc.contains("Flux");
  }

  private String extractResponseType(MethodNode methodNode, MethodInsnNode methodInsn) {
    AbstractInsnNode currentInsn = methodInsn;
    while (currentInsn != null) {
      if (currentInsn instanceof MethodInsnNode) {
        MethodInsnNode mi = (MethodInsnNode) currentInsn;
        if (mi.name.equals("bodyToMono") || mi.name.equals("bodyToFlux")) {
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
    return Type.getReturnType(methodNode.desc).getClassName();
  }

  private String combineUrls(String baseUrl, String endpoint) {
    if (baseUrl == null || endpoint == null) {
      return baseUrl != null ? baseUrl : endpoint;
    }
    baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    endpoint = endpoint.startsWith("/") ? endpoint : "/" + endpoint;
    return baseUrl + endpoint;
  }

  private String resolvePropertyPlaceholders(String input) {
    Pattern pattern = Pattern.compile("\\$\\{([^}]+)\\}");
    Matcher matcher = pattern.matcher(input);
    StringBuffer sb = new StringBuffer();
    while (matcher.find()) {
      String propertyKey = matcher.group(1);
      String propertyValue = configProperties.getOrDefault(propertyKey, propertyKey);
      matcher.appendReplacement(sb, propertyValue);
    }
    matcher.appendTail(sb);
    return sb.toString();
  }

  private Set<String> parseCommaSeparatedConfig(String key) {
    return Arrays.stream(configProperties.getOrDefault(key, "").split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .collect(Collectors.toSet());
  }

  private ClassNode findClassNode(String className) {
    return allClasses.stream()
        .filter(cn -> cn.name.equals(className))
        .findFirst()
        .orElse(null);
  }

  private boolean isController(ClassNode classNode) {
    return hasAnnotation(classNode, "RestController") || hasAnnotation(classNode, "Controller");
  }

  private boolean isService(ClassNode classNode) {
    return hasAnnotation(classNode, "Service") || hasAnnotation(classNode, "Component")
        || hasAnnotation(classNode, "Repository");
  }

  private String extractAnnotationValue(AnnotationNode an, String key) {
    if (an.values != null) {
      for (int i = 0; i < an.values.size(); i += 2) {
        if (an.values.get(i).equals(key)) {
          Object value = an.values.get(i + 1);
          if (value instanceof String[]) {
            return ((String[]) value)[0].replaceAll("\\[|\\]", "");
          } else if (value instanceof String) {
            return ((String) value).replaceAll("\\[|\\]", "");
          }
          return value.toString().replaceAll("\\[|\\]", "");
        }
      }
    }
    return null;
  }
}