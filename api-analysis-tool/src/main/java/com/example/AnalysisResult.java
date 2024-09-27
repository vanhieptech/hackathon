package com.example;

import java.util.List;

public class AnalysisResult {
    private String sequenceDiagram;
    private String classDiagram;
    private String componentDiagram;
    private String stateDiagram;
    private String activityDiagram;
    private ComparisonResult comparisonResult;
    private List<APIInfo> apiInventory;
    private List<ExternalCallInfo> externalCalls;
    private String csvPath;
    private String documentationPath;
    private String htmlDiffPath;

    public AnalysisResult(String sequenceDiagram, String classDiagram, String componentDiagram,
                          String stateDiagram, String activityDiagram, ComparisonResult comparisonResult,
                          List<APIInfo> apiInventory, List<ExternalCallInfo> externalCalls,
                          String csvPath, String documentationPath, String htmlDiffPath) {
        this.sequenceDiagram = sequenceDiagram;
        this.classDiagram = classDiagram;
        this.componentDiagram = componentDiagram;
        this.stateDiagram = stateDiagram;
        this.activityDiagram = activityDiagram;
        this.comparisonResult = comparisonResult;
        this.apiInventory = apiInventory;
        this.externalCalls = externalCalls;
        this.csvPath = csvPath;
        this.documentationPath = documentationPath;
        this.htmlDiffPath = htmlDiffPath;
    }

    // Getters and setters for all fields

    public String getSequenceDiagram() { return sequenceDiagram; }
    public void setSequenceDiagram(String sequenceDiagram) { this.sequenceDiagram = sequenceDiagram; }

    public String getClassDiagram() { return classDiagram; }
    public void setClassDiagram(String classDiagram) { this.classDiagram = classDiagram; }

    public String getComponentDiagram() { return componentDiagram; }
    public void setComponentDiagram(String componentDiagram) { this.componentDiagram = componentDiagram; }

    public String getStateDiagram() { return stateDiagram; }
    public void setStateDiagram(String stateDiagram) { this.stateDiagram = stateDiagram; }

    public String getActivityDiagram() { return activityDiagram; }
    public void setActivityDiagram(String activityDiagram) { this.activityDiagram = activityDiagram; }

    public ComparisonResult getComparisonResult() { return comparisonResult; }
    public void setComparisonResult(ComparisonResult comparisonResult) { this.comparisonResult = comparisonResult; }

    public List<APIInfo> getApiInventory() { return apiInventory; }
    public void setApiInventory(List<APIInfo> apiInventory) { this.apiInventory = apiInventory; }

    public List<ExternalCallInfo> getExternalCalls() { return externalCalls; }
    public void setExternalCalls(List<ExternalCallInfo> externalCalls) { this.externalCalls = externalCalls; }

    public String getCsvPath() { return csvPath; }
    public void setCsvPath(String csvPath) { this.csvPath = csvPath; }

    public String getDocumentationPath() { return documentationPath; }
    public void setDocumentationPath(String documentationPath) { this.documentationPath = documentationPath; }

    public String getHtmlDiffPath() { return htmlDiffPath; }
    public void setHtmlDiffPath(String htmlDiffPath) { this.htmlDiffPath = htmlDiffPath; }
}