package com.analyzer.model;

import java.util.Objects;

public class ApiInfo {
    private String className;
    private String methodName;
    private String returnType;
    private String parameters;

    public ApiInfo(String className, String methodName, String returnType, String parameters) {
        this.className = className;
        this.methodName = methodName;
        this.returnType = returnType;
        this.parameters = parameters;
    }

    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public String getMethodName() {
        return methodName;
    }

    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    public String getReturnType() {
        return returnType;
    }

    public void setReturnType(String returnType) {
        this.returnType = returnType;
    }

    public String getParameters() {
        return parameters;
    }

    public void setParameters(String parameters) {
        this.parameters = parameters;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ApiInfo apiInfo = (ApiInfo) o;
        return Objects.equals(className, apiInfo.className) &&
                Objects.equals(methodName, apiInfo.methodName) &&
                Objects.equals(returnType, apiInfo.returnType) &&
                Objects.equals(parameters, apiInfo.parameters);
    }

    @Override
    public int hashCode() {
        return Objects.hash(className, methodName, returnType, parameters);
    }
}