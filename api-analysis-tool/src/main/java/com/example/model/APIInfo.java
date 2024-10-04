package com.example.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;

public class APIInfo {
  private String serviceName;
  private String baseUrl;
  private List<ExposedAPI> exposedApis;
  private List<ExternalAPI> externalApis;
  private Map<String, String> serviceUrlMap; // For dynamic URL resolution

  public APIInfo(String serviceName, String baseUrl) {
    this.serviceName = serviceName;
    this.baseUrl = baseUrl;
    this.exposedApis = new ArrayList<>();
    this.externalApis = new ArrayList<>();
    this.serviceUrlMap = new HashMap<>();
  }

  public String getBaseUrl() {
    return baseUrl;
  }

  public void setBaseUrl(String baseUrl) {
    this.baseUrl = baseUrl;
  }

  public String getServiceName() {
    return serviceName;
  }

  public void setServiceName(String serviceName) {
    this.serviceName = serviceName;
  }

  public List<ExposedAPI> getExposedApis() {
    return exposedApis;
  }

  public void setExposedApis(List<ExposedAPI> exposedApis) {
    this.exposedApis = exposedApis;
  }

  public List<ExternalAPI> getExternalApis() {
    return externalApis;
  }

  public void setExternalApis(List<ExternalAPI> externalApis) {
    this.externalApis = externalApis;
  }

  public Map<String, String> getServiceUrlMap() {
    return serviceUrlMap;
  }

  public void setServiceUrlMap(Map<String, String> serviceUrlMap) {
    this.serviceUrlMap = serviceUrlMap;
  }

  public static class ExposedAPI {
    private String baseUrl;
    private String path;
    private String httpMethod;
    private List<ExternalAPI> externalApis;
    private boolean isAsync;
    private String serviceName;
    private String serviceClassName;
    private String controllerClassName;
    private String serviceMethod;
    private String returnType;
    private List<ParameterInfo> parameters;
    private Map<String, Set<String>> methodCallTree;
    private boolean isGenerated;
    private Map<String, Set<String>> dependencyTree;

    public ExposedAPI(String path, String httpMethod, boolean isAsync, String serviceClassName,
        String controllerClassName,
        String serviceMethod, String returnType, List<ParameterInfo> parameters, String baseUrl, String serviceName) {
      this.path = path;
      this.httpMethod = httpMethod;
      this.externalApis = new ArrayList<>();
      this.isAsync = isAsync;
      this.serviceClassName = serviceClassName;
      this.controllerClassName = controllerClassName;
      this.serviceMethod = serviceMethod;
      this.returnType = returnType;
      this.parameters = parameters;
      this.methodCallTree = new HashMap<>();
      this.baseUrl = baseUrl;
      this.serviceName = serviceName;
    }

    public String getServiceName() {
      return serviceName;
    }

    public void setServiceName(String serviceName) {
      this.serviceName = serviceName;
    }

    public String getBaseUrl() {
      return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
    }

    public Map<String, Set<String>> getDependencyTree() {
      return dependencyTree;
    }

    public void setDependencyTree(Map<String, Set<String>> dependencyTree) {
      this.dependencyTree = dependencyTree;
    }

    public boolean isGenerated() {
      return isGenerated;
    }

    public void setGenerated(boolean generated) {
      isGenerated = generated;
    }

    public void setMethodCallTree(Map<String, Set<String>> methodCallTree) {
      this.methodCallTree = methodCallTree;
    }

    public Map<String, Set<String>> getMethodCallTree() {
      return methodCallTree;
    }

    // Getters and setters
    public String getPath() {
      return path;
    }

    public void setPath(String path) {
      this.path = path;
    }

    public String getHttpMethod() {
      return httpMethod;
    }

    public void setHttpMethod(String httpMethod) {
      this.httpMethod = httpMethod;
    }

    public List<ExternalAPI> getExternalApis() {
      return externalApis;
    }

    public void setExternalApis(List<ExternalAPI> externalApis) {
      this.externalApis = externalApis;
    }

    public boolean isAsync() {
      return isAsync;
    }

    public void setAsync(boolean async) {
      isAsync = async;
    }

    public void addExternalAPI(ExternalAPI externalAPI) {
      this.externalApis.add(externalAPI);
    }

    public String getServiceClassName() {
      return serviceClassName;
    }

    public void setServiceClassName(String serviceClassName) {
      this.serviceClassName = serviceClassName;
    }

    public String getControllerClassName() {
      return controllerClassName;
    }

    public void setControllerClassName(String controllerClassName) {
      this.controllerClassName = controllerClassName;
    }

    public String getServiceMethod() {
      return serviceMethod;
    }

    public void setServiceMethod(String serviceMethod) {
      this.serviceMethod = serviceMethod;
    }

    public String getReturnType() {
      return returnType;
    }

    public void setReturnType(String returnType) {
      this.returnType = returnType;
    }

    public List<ParameterInfo> getParameters() {
      return parameters;
    }

    public void setParameters(List<ParameterInfo> parameters) {
      this.parameters = parameters;
    }

  }

  public static class ExternalAPI {
    private String serviceName;
    private String path;
    private String httpMethod;
    private boolean isAsync;
    private String communicationType; // e.g., "HTTP", "MessageQueue"
    private List<ParameterInfo> parameters;
    private String returnType;
    private String methodName;
    private boolean isGenerated;
    private String baseUrl;

    public ExternalAPI(String serviceName, String path, String httpMethod, boolean isAsync, String communicationType,
        String returnType, String methodName, String baseUrl) {
      this.serviceName = serviceName;
      this.path = path;
      this.httpMethod = httpMethod;
      this.isAsync = isAsync;
      this.communicationType = communicationType;
      this.parameters = new ArrayList<>();
      this.returnType = returnType;
      this.methodName = methodName;
      this.baseUrl = baseUrl;
    }

    public String getBaseUrl() {
      return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
    }

    // Getters and setters

    public boolean isGenerated() {
      return isGenerated;
    }

    public void setGenerated(boolean generated) {
      isGenerated = generated;
    }

    public String getServiceName() {
      return serviceName;
    }

    public void setServiceName(String serviceName) {
      this.serviceName = serviceName;
    }

    public String getPath() {
      return path;
    }

    public void setPath(String path) {
      this.path = path;
    }

    public String getHttpMethod() {
      return httpMethod;
    }

    public void setHttpMethod(String httpMethod) {
      this.httpMethod = httpMethod;
    }

    public boolean isAsync() {
      return isAsync;
    }

    public void setAsync(boolean async) {
      isAsync = async;
    }

    public String getCommunicationType() {
      return communicationType;
    }

    public void setCommunicationType(String communicationType) {
      this.communicationType = communicationType;
    }

    public List<ParameterInfo> getParameters() {
      return parameters;
    }

    public void setParameters(List<ParameterInfo> parameters) {
      this.parameters = parameters;
    }

    public String getReturnType() {
      return returnType;
    }

    public void setReturnType(String returnType) {
      this.returnType = returnType;
    }

    public String getMethodName() {
      return methodName;
    }

    public void setMethodName(String methodName) {
      this.methodName = methodName;
    }
  }

  public void addExposedAPI(ExposedAPI api) {
    this.exposedApis.add(api);
  }

  public void addServiceUrl(String serviceName, String url) {
    this.serviceUrlMap.put(serviceName, url);
  }

  public String getServiceUrl(String serviceName) {
    return this.serviceUrlMap.get(serviceName);
  }

  public static class ParameterInfo {
    private String name;
    private String type;
    private String annotationType;

    public ParameterInfo(String name, String type, String annotationType) {
      this.name = name;
      this.type = type;
      this.annotationType = annotationType;
    }

    public String getAnnotationType() {
      return annotationType;
    }

    public void setAnnotationType(String annotationType) {
      this.annotationType = annotationType;
    }

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public String getType() {
      return type;
    }

    public void setType(String type) {
      this.type = type;
    }
  }
}