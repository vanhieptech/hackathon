package com.example.model;

import java.util.List;

public class DABDocument {
    private String title;
    private String classDiagram;
    private String sequenceDiagram;
    private List<ApiInfo> apiInfo;
    private List<String> sequenceLogic;
    private List<String> exposedApis;
    private List<String> externalApiCalls;

    // Getters and setters
    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getClassDiagram() {
        return classDiagram;
    }

    public void setClassDiagram(String classDiagram) {
        this.classDiagram = classDiagram;
    }

    public String getSequenceDiagram() {
        return sequenceDiagram;
    }

    public void setSequenceDiagram(String sequenceDiagram) {
        this.sequenceDiagram = sequenceDiagram;
    }

    public List<ApiInfo> getApiInfo() {
        return apiInfo;
    }

    public void setApiInfo(List<ApiInfo> apiInfo) {
        this.apiInfo = apiInfo;
    }

    public List<String> getSequenceLogic() {
        return sequenceLogic;
    }

    public void setSequenceLogic(List<String> sequenceLogic) {
        this.sequenceLogic = sequenceLogic;
    }

    public List<String> getExposedApis() {
        return exposedApis;
    }

    public void setExposedApis(List<String> exposedApis) {
        this.exposedApis = exposedApis;
    }

    public List<String> getExternalApiCalls() {
        return externalApiCalls;
    }

    public void setExternalApiCalls(List<String> externalApiCalls) {
        this.externalApiCalls = externalApiCalls;
    }
}