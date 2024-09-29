package com.example;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ExternalCallInfo {
  private String url;
  private String httpMethod;
  private List<String> parameters = new ArrayList<>();
  private String methodName; // This should be in the format "ClientClassName.methodName"
  private String responseType;
  private String callerMethod;
  private String fallbackMethod;
  private String sdkName;
  private String operation;
  private String serviceName;
  private String description;
  private String callerClassName;
  private String callerMethodName;

  public ExternalCallInfo(String url, String httpMethod, List<String> parameters, String methodName) {
    this.url = url;
    this.httpMethod = httpMethod;
    this.parameters = parameters;
    this.methodName = methodName;
  }

  public ExternalCallInfo(String url, String httpMethod, List<String> parameters, String callerMethod,
      String responseType, String methodName, String fallbackMethod, String sdkName,
      String operation, String serviceName, String description, String callerClassName) {
    this.url = url;
    this.httpMethod = httpMethod;
    this.parameters = parameters;
    this.callerMethod = callerMethod;
    this.responseType = responseType;
    this.methodName = methodName;
    this.fallbackMethod = fallbackMethod;
    this.sdkName = sdkName;
    this.operation = operation;
    this.serviceName = serviceName;
    this.description = description;
    this.callerClassName = callerClassName;
  }

  public String getCallerMethodName() {
    return callerMethodName;
  }

  public void setCallerMethodName(String callerMethodName) {
    this.callerMethodName = callerMethodName;
  }

  // Add getter and setter for callerClassName
  public String getCallerClassName() {
    return callerClassName;
  }

  public void setCallerClassName(String callerClassName) {
    this.callerClassName = callerClassName;
  }

  public String getServiceName() {
    return serviceName;
  }

  public void setServiceName(String serviceName) {
    this.serviceName = serviceName;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public void setCallerMethod(String callerMethod) {
    this.callerMethod = callerMethod;
  }

  public void setFallbackMethod(String fallbackMethod) {
    this.fallbackMethod = fallbackMethod;
  }

  public void setSdkName(String sdkName) {
    this.sdkName = sdkName;
  }

  public void setOperation(String operation) {
    this.operation = operation;
  }

  public ExternalCallInfo(String url, String httpMethod, String purpose2, String methodName, List<String> parameters2) {
    this.url = url;
    this.httpMethod = httpMethod;
    this.parameters = parameters2;
    this.methodName = methodName;
    this.responseType = purpose2;
  }

  public ExternalCallInfo(String serviceName2, String url2, String httpMethod2, String methodName, String description2,
      String responseType2, String[] array) {
    this.serviceName = serviceName2;
    this.url = url2;
    this.httpMethod = httpMethod2;
    this.methodName = methodName;
    this.description = description2;
    this.responseType = responseType2;
    this.parameters = Arrays.asList(array);
  }

  public String getCallerMethod() {
    return callerMethod;
  }

  public String getFallbackMethod() {
    return fallbackMethod;
  }

  public String getSdkName() {
    return sdkName;
  }

  public String getOperation() {
    return operation;
  }

  // Getters
  public String getUrl() {
    return url;
  }

  public String getHttpMethod() {
    return httpMethod;
  }

  public List<String> getParameters() {
    return parameters;
  }

  public String getMethodName() {
    return methodName;
  }

  // Setters
  public void setUrl(String url) {
    this.url = url;
  }

  public void setHttpMethod(String httpMethod) {
    this.httpMethod = httpMethod;
  }

  public void setParameters(List<String> parameters) {
    this.parameters = parameters != null ? parameters : new ArrayList<>();
  }

  public void setMethodName(String methodName) {
    this.methodName = methodName;
  }

  public String getResponseType() {
    return responseType;
  }

  public void setResponseType(String responseType) {
    this.responseType = responseType;
  }
}