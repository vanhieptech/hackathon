package com.example;

import java.util.List;

public class ExternalCallInfo {
  private String url;
  private String httpMethod;
  private String[] parameters;
  private String purpose; // This should be in the format "ClientClassName.methodName"
  private String responseType;
  private String callerMethod;

  public ExternalCallInfo(String url, String httpMethod, String[] parameters, String purpose) {
    this.url = url;
    this.httpMethod = httpMethod;
    this.parameters = parameters;
    this.purpose = purpose;
  }

  public void setCallerMethod(String callerMethod) {
    this.callerMethod = callerMethod;
  }

  public ExternalCallInfo(String url, String httpMethod, String purpose2, String purpose, List<String> parameters2) {
    this.url = url;
    this.httpMethod = httpMethod;
    this.parameters = parameters2.toArray(new String[0]);
    this.purpose = purpose;
    this.responseType = purpose2;
  }

  public String getCallerMethod() {
    return callerMethod;
  }

  // Getters
  public String getUrl() {
    return url;
  }

  public String getHttpMethod() {
    return httpMethod;
  }

  public String[] getParameters() {
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

  public void setParameters(String[] parameters) {
    this.parameters = parameters;
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