package com.example.apianalysistool;

public class ExternalCallInfo {
    private String url;
    private String httpMethod;
    private String[] parameters;
    private String purpose;

    public ExternalCallInfo(String url, String httpMethod, String[] parameters, String purpose) {
        this.url = url;
        this.httpMethod = httpMethod;
        this.parameters = parameters;
        this.purpose = purpose;
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
}