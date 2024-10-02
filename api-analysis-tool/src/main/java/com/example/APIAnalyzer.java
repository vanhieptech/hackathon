package com.example;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.model.APIInfo;
import com.example.model.APIInfo.ExternalAPI;

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
    buildServiceMethodCallMap();
    logger.info("APIAnalyzer initialized with base path: {}", basePath);
  }

  public APIInfo analyzeAPI() {
    logger.info("Starting API analysis for project: {}", serviceName);
    APIInfo apiInfo = new APIInfo(serviceName);
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
          Map<String, Set<String>> dependencyTree = buildDependencyTree(classNode, api.getServiceMethod(), 0,
              new HashSet<>());
          logger.info("Dependency tree for {}.{} built with {} entries", classNode.name, api.getServiceMethod(),
              dependencyTree.size());
          api.setDependencyTree(dependencyTree);
          List<APIInfo.ExternalAPI> externalAPIs = extractExternalAPICalls(classNode, api.getServiceMethod());
          logger.info("Extracted {} external API calls for {}.{}", externalAPIs.size(), classNode.name,
              api.getServiceMethod());
          api.setExternalApis(externalAPIs);
        }
        exposedApis.addAll(apis);
        logger.info("Total exposed APIs after processing {}: {}", classNode.name, exposedApis.size());
      }
    }

    logger.info("Scanning interface implementations");
    for (ClassNode interfaceNode : allClasses) {
      if ((interfaceNode.access & Opcodes.ACC_INTERFACE) != 0) {
        logger.info("Processing interface: {}", interfaceNode.name);
        List<ClassNode> implementingClasses = findClassesImplementingInterface(interfaceNode.name);
        for (ClassNode implementingClass : implementingClasses) {
          logger.info("Processing implementing class: {}", implementingClass.name);
          List<APIInfo.ExposedAPI> apis = extractAPIsFromClass(implementingClass, allClasses);
          for (APIInfo.ExposedAPI api : apis) {
            logger.info("Building dependency tree for API: {}.{}", implementingClass.name, api.getServiceMethod());
            Map<String, Set<String>> dependencyTree = buildDependencyTree(implementingClass, api.getServiceMethod(), 0,
                new HashSet<>());
            api.setDependencyTree(dependencyTree);
            api.setExternalApis(extractExternalAPICalls(implementingClass, api.getServiceMethod()));
          }
          exposedApis.addAll(apis);
        }
      }
    }

    apiInfo.setExposedApis(exposedApis);
    identifyAsyncApis(apiInfo);

    logger.info("Completed API analysis. Extracted {} APIs", exposedApis.size());
    return apiInfo;
  }

  private List<APIInfo.ExternalAPI> extractExternalAPICalls(ClassNode classNode, String serviceMethod) {
    List<APIInfo.ExternalAPI> externalAPIs = new ArrayList<>();
    Map<String, String> classFields = extractClassFields(classNode);
    String baseUrl = extractBaseUrl(classNode, classFields);

    Map<String, Set<String>> dependencyTree = buildDependencyTree(classNode, serviceMethod, 0, new HashSet<>());
    extractExternalAPICallsFromTree(classNode, serviceMethod, dependencyTree,
        baseUrl, classFields, externalAPIs);
    return externalAPIs;
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
      }
    }
  }

  private void analyzeLambdaExpression(ClassNode classNode, MethodNode methodNode, MethodInsnNode methodInsn,
      String baseUrl, Map<String, String> classFields, List<APIInfo.ExternalAPI> externalAPIs) {
    AbstractInsnNode nextInsn = methodInsn.getNext();
    while (nextInsn != null) {
      if (nextInsn instanceof InvokeDynamicInsnNode) {
        InvokeDynamicInsnNode lambdaInsn = (InvokeDynamicInsnNode) nextInsn;
        if (lambdaInsn.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")) {
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
        break;
      }
      nextInsn = nextInsn.getNext();
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

      return new APIInfo.ExternalAPI(targetService, fullUrl, httpMethod, isAsync, type, responseType, callerMethod);
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
    Set<String> visitedMethods = new HashSet<>();
    Map<String, Set<String>> methodCallTree = new HashMap<>();
    buildMethodCallTreeRecursive(classNode, methodName, methodCallTree, visitedMethods, 0);
    exposedAPI.setMethodCallTree(methodCallTree);
  }

  private Map<String, Set<String>> buildDependencyTree(ClassNode classNode, String methodName, int depth,
      Set<String> visitedMethods) {
    if (depth > MAX_DEPTH || visitedMethods.contains(classNode.name + "." + methodName)) {
      logger.warn("Max depth reached or circular dependency detected for method: {}.{}", classNode.name, methodName);
      return new HashMap<>();
    }
    visitedMethods.add(classNode.name + "." + methodName);

    logger.debug("Building dependency tree for {}.{} at depth {}", classNode.name, methodName, depth);
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
    // analyzeMethodCalls(classNode, methodNode, injectedServices, dependencies,
    // dependencyTree, depth, visitedMethods);

    logger.debug("Completed dependency tree for {}.{} at depth {}", classNode.name, methodName, depth);
    return dependencyTree;
  }

  private void analyzeMethodCallsRecursively(ClassNode classNode, MethodNode methodNode, Set<String> injectedServices,
      Set<String> dependencies, Map<String, Set<String>> dependencyTree,
      int depth, Set<String> visitedMethods) {
    for (AbstractInsnNode insn : methodNode.instructions) {
      if (insn instanceof MethodInsnNode) {
        MethodInsnNode methodInsn = (MethodInsnNode) insn;
        String calledMethod = methodInsn.owner.replace('/', '.') + "." + methodInsn.name;
        dependencies.add(calledMethod);

        ClassNode targetClass = findClassNode(methodInsn.owner);
        if (targetClass != null) {
          if (injectedServices.contains(targetClass.name.replace('/', '.'))) {
            if ((targetClass.access & Opcodes.ACC_INTERFACE) != 0) {
              List<ClassNode> implementationClasses = findAllInterfaceImplementations(targetClass);
              for (ClassNode implementationClass : implementationClasses) {
                Map<String, Set<String>> subTree = buildDependencyTree(implementationClass, methodInsn.name, depth + 1,
                    new HashSet<>(visitedMethods));
                mergeDependencyTrees(dependencyTree, subTree);
              }
            } else {
              Map<String, Set<String>> subTree = buildDependencyTree(targetClass, methodInsn.name, depth + 1,
                  new HashSet<>(visitedMethods));
              mergeDependencyTrees(dependencyTree, subTree);
            }
          } else {
            MethodNode targetMethod = findMethodNode(targetClass, methodInsn.name, methodInsn.desc);
            if (targetMethod != null) {
              analyzeMethodCallsRecursively(targetClass, targetMethod, injectedServices, dependencies, dependencyTree,
                  depth + 1, new HashSet<>(visitedMethods));
            }
          }
        }

        if (isOptionalMapMethod(methodInsn)) {
          analyzeLambdaExpression(classNode, methodNode, methodInsn, dependencyTree, visitedMethods, depth);
        }
      }
    }
  }

  private List<ClassNode> findAllInterfaceImplementations(ClassNode interfaceNode) {
    return allClasses.stream()
        .filter(cn -> cn.interfaces.contains(interfaceNode.name))
        .collect(Collectors.toList());
  }

  private void mergeDependencyTrees(Map<String, Set<String>> target, Map<String, Set<String>> source) {
    for (Map.Entry<String, Set<String>> entry : source.entrySet()) {
      target.merge(entry.getKey(), new HashSet<>(entry.getValue()), (existing, newSet) -> {
        existing.addAll(newSet);
        return existing;
      });
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
          APIInfo.ExternalAPI externalAPI = new APIInfo.ExternalAPI(topic, "PUBLISH", methodInsn.name, true, brokerType,
              returnType, classNode.name + "." + methodNode.name);
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

    // Check fields for @Autowired or @Inject annotations
    for (FieldNode field : classNode.fields) {
      if (hasAnnotation(field, "Autowired") || hasAnnotation(field, "Inject")) {
        injectedServices.add(Type.getType(field.desc).getClassName());
      }
    }

    // Check constructor for injected services
    for (MethodNode method : classNode.methods) {
      if (method.name.equals("<init>")) {
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

  private void buildMethodCallTreeRecursive(ClassNode classNode, String methodName,
      Map<String, Set<String>> methodCallTree, Set<String> visitedMethods, int depth) {
    if (depth > MAX_DEPTH || visitedMethods.contains(classNode.name + "." + methodName)) {
      return;
    }
    visitedMethods.add(classNode.name + "." + methodName);

    MethodNode methodNode = findMethodNode(classNode, methodName);
    if (methodNode == null) {
      return;
    }

    Set<String> methodCalls = new HashSet<>();
    methodCallTree.put(classNode.name + "." + methodName, methodCalls);

    Set<String> injectedServices = detectInjectedServices(classNode);

    for (AbstractInsnNode insn : methodNode.instructions) {
      if (insn instanceof MethodInsnNode) {
        MethodInsnNode methodInsn = (MethodInsnNode) insn;
        String calledMethod = methodInsn.owner.replace('/', '.') + "." + methodInsn.name;
        methodCalls.add(calledMethod);

        if (injectedServices.contains(methodInsn.owner.replace('/', '.'))) {
          ClassNode targetClass = findClassNode(methodInsn.owner);
          if (targetClass != null) {
            buildMethodCallTreeRecursive(targetClass, methodInsn.name, methodCallTree, new HashSet<>(visitedMethods),
                depth + 1);
          }
        }

        if (isOptionalMapMethod(methodInsn)) {
          analyzeLambdaExpression(classNode, methodNode, methodInsn, methodCallTree, visitedMethods, depth);
        }
      }
    }
  }

  private void analyzeLambdaExpression(ClassNode classNode, MethodNode methodNode, MethodInsnNode methodInsn,
      Map<String, Set<String>> dependencyTree, Set<String> visitedMethods, int depth) {
    AbstractInsnNode nextInsn = methodInsn.getNext();
    while (nextInsn != null) {
      if (nextInsn instanceof InvokeDynamicInsnNode) {
        InvokeDynamicInsnNode lambdaInsn = (InvokeDynamicInsnNode) nextInsn;
        if (lambdaInsn.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")) {
          String lambdaMethod = lambdaInsn.name;
          ClassNode lambdaClass = findClassNode(classNode.name + "$Lambda");
          if (lambdaClass != null) {
            MethodNode lambdaMethodNode = findMethodNode(lambdaClass, lambdaMethod);
            if (lambdaMethodNode != null) {
              Set<String> injectedServices = detectInjectedServices(lambdaClass);
              analyzeMethodCallsRecursively(lambdaClass, lambdaMethodNode, injectedServices, new HashSet<>(),
                  dependencyTree, depth + 1, new HashSet<>(visitedMethods));
            }
          } else {
            // Handle anonymous lambda
            for (MethodNode method : classNode.methods) {
              if (method.name.startsWith("lambda$") && method.desc.equals(lambdaInsn.desc)) {
                Set<String> injectedServices = detectInjectedServices(classNode);
                analyzeMethodCallsRecursively(classNode, method, injectedServices, new HashSet<>(), dependencyTree,
                    depth + 1, new HashSet<>(visitedMethods));
                break;
              }
            }
          }
        }
        break;
      }
      nextInsn = nextInsn.getNext();
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
          parameters);
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

    // // Check if serviceMethod is not null or empty
    // if (serviceMethod == null || serviceMethod.isEmpty()) {
    // logger.info("Invalid API: Service method is null or empty");
    // return false;
    // }

    // // Check if controllerClassName is not null or empty
    // if (controllerClassName == null || controllerClassName.isEmpty()) {
    // logger.info("Invalid API: Controller class name is null or empty");
    // return false;
    // }

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
    // First, try to find a field with @Autowired or @Inject annotation
    for (FieldNode field : classNode.fields) {
      if (hasAnnotation(field, "Autowired") || hasAnnotation(field, "Inject")) {
        return Type.getType(field.desc).getClassName();
      }
    }

    // If not found, try to infer from the class name
    String className = classNode.name.substring(classNode.name.lastIndexOf('/') + 1);
    if (className.endsWith("Controller")) {
      String serviceName = className.substring(0, className.length() - "Controller".length()) + "Service";

      // Check if the inferred service class exists
      if (findClassNode(serviceName) != null) {
        return serviceName;
      }
    }

    // If still not found, search for a class with "Service" suffix in the same
    // package
    String packageName = classNode.name.substring(0, classNode.name.lastIndexOf('/') + 1);
    for (ClassNode cn : allClasses) {
      if (cn.name.startsWith(packageName) && cn.name.endsWith("Service")) {
        return cn.name.replace('/', '.');
      }
    }

    // If no service class is found, return the original class name
    logger.warn("No service class found for {}. Using the class itself.", classNode.name);
    return classNode.name.replace('/', '.');
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
          externalAPIs.add(new APIInfo.ExternalAPI(topic, "PUBLISH", methodInsn.name, true, brokerType,
              returnType, serviceMethod));
        }
      }
    }
    return externalAPIs;
  }

  private boolean isMessageBrokerMethod(MethodInsnNode methodInsn) {
    return methodInsn.owner.contains("KafkaTemplate") ||
        methodInsn.owner.contains("RabbitTemplate") ||
        methodInsn.owner.contains("JmsTemplate");
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

  private List<APIInfo.ExternalAPI> extractFeignClientCalls(String controllerClassName, String serviceMethod,
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
        ClassNode feignClientClass = findFeignClientClass(methodInsn.owner, allClasses);
        if (feignClientClass != null) {
          String baseUrl = extractFeignClientBaseUrl(feignClientClass);
          MethodNode feignMethod = findMethodNode(feignClientClass, methodInsn.name, methodInsn.desc);
          if (feignMethod != null) {
            String httpMethod = extractFeignMethodHttpMethod(feignMethod);
            String path = extractFeignMethodPath(feignMethod);
            String fullUrl = baseUrl + path;
            externalAPIs.add(
                new APIInfo.ExternalAPI(serviceMethod, path, httpMethod, false, fullUrl, "", ""));
          }
        }
      }
    }
    return externalAPIs;
  }

  private String extractFeignMethodPath(MethodNode feignMethod) {
    if (feignMethod.visibleAnnotations != null) {
      for (AnnotationNode an : feignMethod.visibleAnnotations) {
        if (an.desc.contains("RequestMapping") ||
            an.desc.contains("GetMapping") ||
            an.desc.contains("PostMapping") ||
            an.desc.contains("PutMapping") ||
            an.desc.contains("DeleteMapping") ||
            an.desc.contains("PatchMapping")) {
          return extractAnnotationValue(an, "value");
        }
      }
    }
    return "";
  }

  private String extractFeignMethodHttpMethod(MethodNode feignMethod) {
    if (feignMethod.visibleAnnotations != null) {
      for (AnnotationNode an : feignMethod.visibleAnnotations) {
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
          String method = extractAnnotationValue(an, "method");
          if (method != null) {
            return method.toUpperCase();
          }
        }
      }
    }
    return "UNKNOWN";
  }

  private String extractFeignClientBaseUrl(ClassNode feignClientClass) {
    if (feignClientClass.visibleAnnotations != null) {
      for (AnnotationNode an : feignClientClass.visibleAnnotations) {
        if (an.desc.contains("FeignClient")) {
          String url = extractAnnotationValue(an, "url");
          if (url != null) {
            return url;
          }
          String name = extractAnnotationValue(an, "name");
          if (name != null) {
            return "${" + name + "}";
          }
        }
      }
    }
    return "";
  }

  private ClassNode findFeignClientClass(String className, List<ClassNode> allClasses) {
    return allClasses.stream()
        .filter(cn -> cn.name.equals(className) && hasFeignClientAnnotation(cn))
        .findFirst()
        .orElse(null);
  }

  private String extractTargetService(MethodInsnNode methodInsn) {
    ClassNode targetClass = findClassNode(methodInsn.owner);
    if (targetClass != null && targetClass.visibleAnnotations != null) {
      for (AnnotationNode an : targetClass.visibleAnnotations) {
        if (an.desc.contains("FeignClient")) {
          return extractAnnotationValue(an, "name");
        }
      }
    }
    return "UnknownService";
  }

  private String extractHttpMethodFromHttpClientCall(MethodInsnNode methodInsn) {
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
    if (methodName.equals("exchange") || methodName.equals("send") || methodName.equals("sendAsync")) {
      // For these methods, we need to check the method arguments to determine the
      // HTTP method
      return extractHttpMethodFromArguments(methodInsn);
    }
    return "UNKNOWN";
  }

  private String extractHttpMethodFromArguments(MethodInsnNode methodInsn) {
    for (AbstractInsnNode insn = methodInsn.getPrevious(); insn != null; insn = insn.getPrevious()) {
      if (insn instanceof FieldInsnNode) {
        FieldInsnNode fieldInsn = (FieldInsnNode) insn;
        if (fieldInsn.owner.contains("HttpMethod")) {
          return fieldInsn.name;
        }
      }
    }
    return "UNKNOWN";
  }

  private String extractUrlFromHttpClientCall(MethodInsnNode methodInsn, Map<String, String> configProperties) {
    String url = null;
    if (methodInsn.owner.contains("RestTemplate")) {
      url = extractUrlFromRestTemplateCall(methodInsn);
    } else if (methodInsn.owner.contains("WebClient")) {
      url = extractUrlFromWebClientCall(methodInsn);
    } else if (methodInsn.owner.contains("HttpClient")) {
      url = extractUrlFromHttpClientCall(methodInsn);
    }

    if (url != null) {
      // Resolve placeholders using configProperties
      for (Map.Entry<String, String> entry : configProperties.entrySet()) {
        url = url.replace("${" + entry.getKey() + "}", entry.getValue());
      }
    }
    return url;
  }

  private String extractUrlFromRestTemplateCall(MethodInsnNode methodInsn) {
    for (AbstractInsnNode insn = methodInsn.getPrevious(); insn != null; insn = insn.getPrevious()) {
      if (insn instanceof LdcInsnNode) {
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

  private String extractUrlFromWebClientCall(MethodInsnNode methodInsn) {
    for (AbstractInsnNode insn = methodInsn.getPrevious(); insn != null; insn = insn.getPrevious()) {
      if (insn instanceof MethodInsnNode) {
        MethodInsnNode prevMethodInsn = (MethodInsnNode) insn;
        if (prevMethodInsn.name.equals("uri")) {
          return extractStringArgument(prevMethodInsn);
        }
      }
    }
    return null;
  }

  private String extractUrlFromHttpClientCall(MethodInsnNode methodInsn) {
    for (AbstractInsnNode insn = methodInsn.getPrevious(); insn != null; insn = insn.getPrevious()) {
      if (insn instanceof MethodInsnNode) {
        MethodInsnNode prevMethodInsn = (MethodInsnNode) insn;
        if (prevMethodInsn.name.equals("create")) {
          return extractStringArgument(prevMethodInsn);
        }
      }
    }
    return null;
  }

  private String extractStringArgument(MethodInsnNode methodInsn) {
    for (AbstractInsnNode insn = methodInsn.getPrevious(); insn != null; insn = insn.getPrevious()) {
      if (insn instanceof LdcInsnNode) {
        LdcInsnNode ldcInsn = (LdcInsnNode) insn;
        if (ldcInsn.cst instanceof String) {
          return (String) ldcInsn.cst;
        }
      }
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
    return allClasses.stream()
        .filter(classNode -> classNode.interfaces.contains(interfaceName))
        .collect(Collectors.toList());
  }

  private void extractExternalAPICallsRecursive(ClassNode classNode, String methodName, String baseUrl,
      Map<String, String> classFields, List<APIInfo.ExternalAPI> externalAPIs,
      Set<String> visitedMethods, int depth) {
    if (depth > MAX_DEPTH || visitedMethods.contains(classNode.name + "." + methodName)) {
      return;
    }
    visitedMethods.add(classNode.name + "." + methodName);

    MethodNode methodNode = findMethodNode(classNode, methodName);
    if (methodNode == null) {
      // Check if it's an interface method
      for (String interfaceName : classNode.interfaces) {
        ClassNode interfaceNode = findClassNode(interfaceName);
        if (interfaceNode != null) {
          methodNode = findMethodNode(interfaceNode, methodName);
          if (methodNode != null) {
            break;
          }
        }
      }
      if (methodNode == null)
        return;
    }

    for (AbstractInsnNode insn : methodNode.instructions) {
      if (insn instanceof MethodInsnNode) {
        MethodInsnNode methodInsn = (MethodInsnNode) insn;
        if (isHttpCMethod(methodInsn)) {
          APIInfo.ExternalAPI externalAPI = extractExternalAPIInfo(classNode, methodNode, methodInsn, baseUrl,
              classFields);
          externalAPIs.add(externalAPI);
        } else if (isOptionalMapMethod(methodInsn)) {
          analyzeLambdaExpression(classNode, methodNode, methodInsn, baseUrl, classFields, externalAPIs, visitedMethods,
              depth);
        } else {
          ClassNode targetClass = findClassNode(methodInsn.owner);
          if (targetClass != null) {
            extractExternalAPICallsRecursive(targetClass, methodInsn.name, baseUrl, classFields, externalAPIs,
                visitedMethods, depth + 1);
          }
        }
      }
    }
  }

  private boolean isOptionalMapMethod(MethodInsnNode methodInsn) {
    return methodInsn.owner.equals("java/util/Optional") && methodInsn.name.equals("map");
  }

  private void analyzeLambdaExpression(ClassNode classNode, MethodNode methodNode, MethodInsnNode methodInsn,
      String baseUrl, Map<String, String> classFields, List<APIInfo.ExternalAPI> externalAPIs,
      Set<String> visitedMethods, int depth) {
    AbstractInsnNode nextInsn = methodInsn.getNext();
    while (nextInsn != null) {
      if (nextInsn instanceof InvokeDynamicInsnNode) {
        InvokeDynamicInsnNode lambdaInsn = (InvokeDynamicInsnNode) nextInsn;
        if (lambdaInsn.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")) {
          String lambdaMethod = lambdaInsn.name;
          ClassNode lambdaClass = findClassNode(classNode.name + "$Lambda");
          if (lambdaClass != null) {
            MethodNode lambdaMethodNode = findMethodNode(lambdaClass, lambdaMethod);
            if (lambdaMethodNode != null) {
              extractExternalAPICallsRecursive(lambdaClass, lambdaMethod, baseUrl, classFields, externalAPIs,
                  new HashSet<>(visitedMethods), depth + 1);
            }
          } else {
            // Handle anonymous lambda
            for (MethodNode method : classNode.methods) {
              if (method.name.startsWith("lambda$") && method.desc.equals(lambdaInsn.desc)) {
                extractExternalAPICallsRecursive(classNode, method.name, baseUrl, classFields, externalAPIs,
                    new HashSet<>(visitedMethods), depth + 1);
                break;
              }
            }
          }
        }
        break;
      }
      nextInsn = nextInsn.getNext();
    }
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

  private boolean hasFeignClientAnnotation(ClassNode classNode) {
    return classNode.visibleAnnotations != null &&
        classNode.visibleAnnotations.stream()
            .anyMatch(an -> an.desc.contains("FeignClient"));
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
    String fullUrl = combineUrls(baseUrl, endpoint);
    logger.info("Combined full URL: {}", fullUrl);
    String httpMethod = extractHttpMethod(methodInsn);
    logger.info("Extracted HTTP method: {}", httpMethod);
    String targetService = extractTargetService(methodInsn);
    logger.info("Extracted target service: {}", targetService);
    boolean isAsync = isAsyncCall(methodInsn);
    logger.info("Is async call: {}", isAsync);
    String responseType = extractResponseType(methodNode, methodInsn);
    logger.info("Extracted response type: {}", responseType);

    APIInfo.ExternalAPI externalAPI = new APIInfo.ExternalAPI(targetService, fullUrl, httpMethod, isAsync, "HTTP",
        responseType, classNode.name + "." + methodNode.name);
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
      return null;
    for (AnnotationNode an : annotations) {
      if (an.desc.contains(annotationName)) {
        return extractPathValue(an);
      }
    }
    return null;
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
    return null;
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
    if (baseUrl == null)
      return endpoint;
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
          return value instanceof String[] ? ((String[]) value)[0] : value.toString();
        }
      }
    }
    return null;
  }
}