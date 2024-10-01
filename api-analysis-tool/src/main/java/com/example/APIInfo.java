package com.example;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

public class APIInfo {
  private String serviceName;
  private List<ExposedAPI> exposedApis;
  private List<ExternalAPI> externalApis;
  private List<String> dependencies;
  private Map<String, String> controllerToServiceMap;
  private Map<String, List<String>> flow;
  private String version;

  public APIInfo(String serviceName) {
    this.serviceName = serviceName;
    this.exposedApis = new ArrayList<>();
    this.externalApis = new ArrayList<>();
    this.dependencies = new ArrayList<>();
    this.flow = new HashMap<>();
  }

  // Getters and setters

  public APIInfo(String serviceName, List<ExposedAPI> exposedApis, List<ExternalAPI> externalApis,
      List<String> dependencies, Map<String, List<String>> flow, String version) {
    this.serviceName = serviceName;
    this.exposedApis = exposedApis;
    this.externalApis = externalApis;
    this.dependencies = dependencies;
    this.flow = flow;
    this.version = version;
  }

  public Map<String, String> getControllerToServiceMap() {
    return controllerToServiceMap;
  }

  public void setControllerToServiceMap(Map<String, String> controllerToServiceMap) {
    this.controllerToServiceMap = controllerToServiceMap;
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

  public List<String> getDependencies() {
    return dependencies;
  }

  public Map<String, List<String>> getFlow() {
    return flow;
  }

  public void setFlow(Map<String, List<String>> flow) {
    this.flow = flow;
  }

  public String getVersion() {
    return version;
  }

  public void setVersion(String version) {
    this.version = version;
  }

  public static class ExposedAPI {
    private String path;
    private String httpMethod;
    private String serviceMethod;
    private List<ParameterInfo> parameters;
    private String returnType;
    private String controllerClassName;
    private String serviceClassName;
    private boolean isAsync;
    private List<ExternalAPI> externalAPIs;
    private String version;

    // Constructor, getters, and setters

    public ExposedAPI(String path, String httpMethod, String serviceMethod, List<ParameterInfo> parameters,
        String returnType, String controllerClassName, String serviceClassName, boolean isAsync,
        List<ExternalAPI> externalAPIs, String version) {
      this.path = path;
      this.httpMethod = httpMethod;
      this.serviceMethod = serviceMethod;
      this.parameters = parameters;
      this.returnType = returnType;
      this.controllerClassName = controllerClassName;
      this.serviceClassName = serviceClassName;
      this.isAsync = isAsync;
      this.externalAPIs = externalAPIs;
      this.version = version;
    }

    public String getVersion() {
      return version;
    }

    public void setVersion(String version) {
      this.version = version;
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

    public String getServiceMethod() {
      return serviceMethod;
    }

    public void setServiceMethod(String serviceMethod) {
      this.serviceMethod = serviceMethod;
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

    public String getControllerClassName() {
      return controllerClassName;
    }

    public void setControllerClassName(String controllerClassName) {
      this.controllerClassName = controllerClassName;
    }

    public String getServiceClassName() {
      return serviceClassName;
    }

    public void setServiceClassName(String serviceClassName) {
      this.serviceClassName = serviceClassName;
    }

    public boolean isAsync() {
      return isAsync;
    }

    public void setAsync(boolean async) {
      isAsync = async;
    }

    public List<ExternalAPI> getExternalAPIs() {
      return externalAPIs;
    }

    public void setExternalAPIs(List<ExternalAPI> externalAPIs) {
      this.externalAPIs = externalAPIs;
    }
  }

  public static class ExternalAPI {
    private String url;
    private String httpMethod;
    private String callingMethod;
    private boolean isAsync;
    private String targetService;
    private String responseType;

    // Constructor, getters, and setters

    public ExternalAPI(String url, String httpMethod, String callingMethod, boolean isAsync, String targetService,
        String responseType) {
      this.url = url;
      this.httpMethod = httpMethod;
      this.callingMethod = callingMethod;
      this.isAsync = isAsync;
      this.targetService = targetService;
      this.responseType = responseType;
    }

    public String getUrl() {
      return url;
    }

    public void setUrl(String url) {
      this.url = url;
    }

    public String getHttpMethod() {
      return httpMethod;
    }

    public void setHttpMethod(String httpMethod) {
      this.httpMethod = httpMethod;
    }

    public String getCallingMethod() {
      return callingMethod;
    }

    public void setCallingMethod(String callingMethod) {
      this.callingMethod = callingMethod;
    }

    public boolean isAsync() {
      return isAsync;
    }

    public void setAsync(boolean async) {
      isAsync = async;
    }

    public String getTargetService() {
      return targetService;
    }

    public void setTargetService(String targetService) {
      this.targetService = targetService;
    }

    public String getResponseType() {
      return responseType;
    }

    public void setResponseType(String responseType) {
      this.responseType = responseType;
    }
  }

  public static class ParameterInfo {
    private String type;
    private String name;
    private String annotationType;
    // Constructor, getters, and setters

    public ParameterInfo(String type, String name, String annotationType) {
      this.type = type;
      this.name = name;
      this.annotationType = annotationType;
    }

    public String getType() {
      return type;
    }

    public void setType(String type) {
      this.type = type;
    }

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public String getAnnotationType() {
      return annotationType;
    }

    public void setAnnotationType(String annotationType) {
      this.annotationType = annotationType;
    }
  }

  // Add methods to add ExposedAPI and ExternalAPI objects
  public void addExposedAPI(ExposedAPI api) {
    this.exposedApis.add(api);
  }

  public void addExternalAPI(ExternalAPI api) {
    this.externalApis.add(api);
  }

  // Add method to set dependencies
  public void setDependencies(List<String> dependencies) {
    this.dependencies = dependencies;
  }

  // Add method to add flow information
  public void addFlow(String key, List<String> steps) {
    this.flow.put(key, steps);
  }
}