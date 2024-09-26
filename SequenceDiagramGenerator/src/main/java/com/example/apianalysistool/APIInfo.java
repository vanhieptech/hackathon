package com.example.apianalysistool;

public class APIInfo {
    private String methodName;
    private String httpMethod;
    private String path;
    private String[] parameters;
    private String returnType;

    public APIInfo(String methodName, String httpMethod, String path, String[] parameters, String returnType) {
        this.methodName = methodName;
        this.httpMethod = httpMethod;
        this.path = path;
        this.parameters = parameters;
        this.returnType = returnType;
    }

    // Getters
    public String getMethodName() { return methodName; }
    public String getHttpMethod() { return httpMethod; }
    public String getPath() { return path; }
    public String[] getParameters() { return parameters; }
    public String getReturnType() { return returnType; }

    // Setters
    public void setMethodName(String methodName) { this.methodName = methodName; }
    public void setHttpMethod(String httpMethod) { this.httpMethod = httpMethod; }
    public void setPath(String path) { this.path = path; }
    public void setParameters(String[] parameters) { this.parameters = parameters; }
    public void setReturnType(String returnType) { this.returnType = returnType; }
}