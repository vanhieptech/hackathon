package com.example;

import java.util.ArrayList;
import java.util.List;

public class ExternalCallInfo {
  private String url;
  private String httpMethod;
  private List<String> parameters = new ArrayList<>();
  private String purpose; // This should be in the format "ClientClassName.methodName"
  private String responseType;
  private String callerMethod;
  private String fallbackMethod;
  private String sdkName;
  private String operation;
  private String serviceName;
  private String description;
  private String callerClassName;
  private String callerMethodName;

  public ExternalCallInfo(String url, String httpMethod, List<String> parameters, String purpose) {
    this.url = url;
    this.httpMethod = httpMethod;
    this.parameters = parameters;
    this.purpose = purpose;
  }

  public ExternalCallInfo(String serviceName, String url, String httpMethod, String purpose, String description,
      String responseType, List<String> parameters) {
    this.serviceName = serviceName;
    this.url = url;
    this.httpMethod = httpMethod;
    this.purpose = purpose;
    this.description = description;
    this.responseType = responseType;
    this.parameters = parameters;
  }

  public ExternalCallInfo(String url, String httpMethod, List<String> parameters, String purpose, String responseType,
      String callerMethod, String fallbackMethod, String sdkName, String operation, String serviceName,
      String description, String callerClassName, String callerMethodName) {
    this.url = url;
    this.httpMethod = httpMethod;
    this.parameters = parameters;
    this.purpose = purpose;
    this.responseType = responseType;
    this.callerMethod = callerMethod;
    this.fallbackMethod = fallbackMethod;
    this.sdkName = sdkName;
    this.operation = operation;
    this.serviceName = serviceName;
    this.description = description;
    this.callerClassName = callerClassName;
    this.callerMethodName = callerMethodName;
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

  public ExternalCallInfo(String url, String httpMethod, String purpose2, String purpose, List<String> parameters2) {
    this.url = url;
    this.httpMethod = httpMethod;
    this.parameters = parameters2;
    this.purpose = purpose;
    this.responseType = purpose2;
  }

  public ExternalCallInfo(String serviceName2, String url2, String httpMethod2, String purpose2, String description2,
      String responseType2, String[] array) {
    // TODO Auto-generated constructor stub
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

  public String getPurpose() {
    return purpose;
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

  public void setPurpose(String purpose) {
    this.purpose = purpose;
  }

  public String getResponseType() {
    return responseType;
  }

  public void setResponseType(String responseType) {
    this.responseType = responseType;
  }
}