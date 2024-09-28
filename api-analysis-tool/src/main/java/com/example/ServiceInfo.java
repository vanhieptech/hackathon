package com.example;

import java.util.List;

public class ServiceInfo {
    private String serviceType;
    private String methodName;
    private String callerMethod;
    private List<String> parameters;

    public ServiceInfo(String serviceType, String methodName, String callerMethod, List<String> parameters) {
        this.serviceType = serviceType;
        this.methodName = methodName;
        this.callerMethod = callerMethod;
        this.parameters = parameters;
    }

    // Add getters and setters for all fields

    public String getServiceType() {
        return serviceType;
    }

    public void setServiceType(String serviceType) {
        this.serviceType = serviceType;
    }

    public String getMethodName() {
        return methodName;
    }

    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    public String getCallerMethod() {
        return callerMethod;
    }

    public void setCallerMethod(String callerMethod) {
        this.callerMethod = callerMethod;
    }

    public List<String> getParameters() {
        return parameters;
    }

    public void setParameters(List<String> parameters) {
        this.parameters = parameters;
    }
}