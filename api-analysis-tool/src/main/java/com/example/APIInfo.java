package com.example;

import java.util.List;

public class APIInfo {
    private String httpMethod;
    private String path;
    private String methodName;
    private String returnType;
    private List<String> parameters;

    public APIInfo(String httpMethod, String path, String methodName, String returnType, List<String> parameters) {
        this.httpMethod = httpMethod;
        this.path = path;
        this.methodName = methodName;
        this.returnType = returnType;
        this.parameters = parameters;
    }

    // Getters and setters
    public String getHttpMethod() { return httpMethod; }
    public void setHttpMethod(String httpMethod) { this.httpMethod = httpMethod; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public String getMethodName() { return methodName; }
    public void setMethodName(String methodName) { this.methodName = methodName; }

    public String getReturnType() { return returnType; }
    public void setReturnType(String returnType) { this.returnType = returnType; }

    public List<String> getParameters() { return parameters; }
    public void setParameters(List<String> parameters) { this.parameters = parameters; }
}