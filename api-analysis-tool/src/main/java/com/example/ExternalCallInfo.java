package com.example;

public class ExternalCallInfo {
    private String url;
    private String httpMethod;
    private String[] parameters;
    private String purpose; // This should be in the format "ClientClassName.methodName"
    private String responseType;

    public ExternalCallInfo(String url, String httpMethod, String[] parameters, String purpose) {
        this.url = url;
        this.httpMethod = httpMethod;
        this.parameters = parameters;
        this.purpose = purpose;
    }

    public ExternalCallInfo(String url, String httpMethod, String[] parameters, String purpose, String responseType) {
        this.url = url;
        this.httpMethod = httpMethod;
        this.parameters = parameters;
        this.purpose = purpose;
        this.responseType = responseType;
    }

    // Getters
    public String getUrl() { return url; }
    public String getHttpMethod() { return httpMethod; }
    public String[] getParameters() { return parameters; }
    public String getPurpose() { return purpose; }

    // Setters
    public void setUrl(String url) { this.url = url; }
    public void setHttpMethod(String httpMethod) { this.httpMethod = httpMethod; }
    public void setParameters(String[] parameters) { this.parameters = parameters; }
    public void setPurpose(String purpose) { this.purpose = purpose; }
    public String getResponseType() {
        return responseType;
    }

    public void setResponseType(String responseType) {
        this.responseType = responseType;
    }
}