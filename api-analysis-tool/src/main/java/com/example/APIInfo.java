package com.example;

import java.util.ArrayList;
import java.util.List;

public class APIInfo {
  private String serviceName;
  private String apiName;
  private String description;
  private String apiEndpoint;
  private String path;
  private String httpMethod;
  private String methodName;
  private String version;
  private List<String> serviceDependencies;
  private String returnType;
  private List<ParameterInfo> parameters;
  private boolean isAsync;

  public APIInfo(String serviceName, String apiName, String methodName, String description, String apiEndpoint,
      String path, String httpMethod, String version, List<String> serviceDependencies,
      String returnType, List<ParameterInfo> parameters, boolean isAsync) {
    this.serviceName = serviceName;
    this.apiName = apiName;
    this.description = description;
    this.apiEndpoint = apiEndpoint;
    this.path = path;
    this.httpMethod = httpMethod;
    this.methodName = methodName;
    this.version = version;
    this.serviceDependencies = serviceDependencies;
    this.returnType = returnType;
    this.parameters = parameters;
    this.isAsync = isAsync;
  }

  // Getters
  public String getServiceName() {
    return serviceName;
  }

  public String getApiName() {
    return apiName;
  }

  public String getMethodName() {
    return methodName;
  }

  public String getDescription() {
    return description;
  }

  public String getApiEndpoint() {
    return apiEndpoint;
  }

  public String getPath() {
    return path;
  }

  public String getHttpMethod() {
    return httpMethod;
  }

  public String getVersion() {
    return version;
  }

  public List<String> getServiceDependencies() {
    return serviceDependencies;
  }

  public String getReturnType() {
    return returnType;
  }

  public List<ParameterInfo> getParameters() {
    return parameters;
  }

  public boolean isAsync() {
    return isAsync;
  }

  // Setters
  public void setServiceName(String serviceName) {
    this.serviceName = serviceName;
  }

  public void setApiName(String apiName) {
    this.apiName = apiName;
  }

  public void setMethodName(String methodName) {
    this.methodName = methodName;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public void setApiEndpoint(String apiEndpoint) {
    this.apiEndpoint = apiEndpoint;
  }

  public void setPath(String path) {
    this.path = path;
  }

  public void setHttpMethod(String httpMethod) {
    this.httpMethod = httpMethod;
  }

  public void setVersion(String version) {
    this.version = version;
  }

  public void setServiceDependencies(List<String> serviceDependencies) {
    this.serviceDependencies = serviceDependencies != null ? serviceDependencies : new ArrayList<>();
  }

  public void setReturnType(String returnType) {
    this.returnType = returnType;
  }

  public void setParameters(List<ParameterInfo> parameters) {
    this.parameters = parameters != null ? parameters : new ArrayList<>();
  }

  public void setAsync(boolean async) {
    isAsync = async;
  }

  public static class ParameterInfo {
    public String type;
    public String name;
    public String annotationType;

    public ParameterInfo(String type, String name, String annotationType) {
      this.type = type;
      this.name = name;
      this.annotationType = annotationType;
    }
  }
}