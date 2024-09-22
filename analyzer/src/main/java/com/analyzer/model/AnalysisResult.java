package com.analyzer.model;

import java.util.List;

public class AnalysisResult {
    private String id;
    private List<UmlDiagram> umlDiagrams;
    private List<ApiInfo> apiInfo;
    private ComparisonResult comparisonResult;
    private CodeQualityMetrics codeQualityMetrics;
    private List<DatabaseChange> databaseChanges;
    private List<String> sequenceLogic;
    private List<String> exposedApis;
    private List<String> externalApiCalls;

    public AnalysisResult(String id, List<UmlDiagram> umlDiagrams, List<ApiInfo> apiInfo,
                          ComparisonResult comparisonResult, CodeQualityMetrics codeQualityMetrics,
                          List<DatabaseChange> databaseChanges, List<String> sequenceLogic,
                          List<String> exposedApis, List<String> externalApiCalls) {
        this.id = id;
        this.umlDiagrams = umlDiagrams;
        this.apiInfo = apiInfo;
        this.comparisonResult = comparisonResult;
        this.codeQualityMetrics = codeQualityMetrics;
        this.databaseChanges = databaseChanges;
        this.sequenceLogic = sequenceLogic;
        this.exposedApis = exposedApis;
        this.externalApiCalls = externalApiCalls;
    }

    // Getters and setters
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public List<UmlDiagram> getUmlDiagrams() {
        return umlDiagrams;
    }

    public void setUmlDiagrams(List<UmlDiagram> umlDiagrams) {
        this.umlDiagrams = umlDiagrams;
    }

    public List<ApiInfo> getApiInfo() {
        return apiInfo;
    }

    public void setApiInfo(List<ApiInfo> apiInfo) {
        this.apiInfo = apiInfo;
    }

    public ComparisonResult getComparisonResult() {
        return comparisonResult;
    }

    public void setComparisonResult(ComparisonResult comparisonResult) {
        this.comparisonResult = comparisonResult;
    }

    public CodeQualityMetrics getCodeQualityMetrics() {
        return codeQualityMetrics;
    }

    public void setCodeQualityMetrics(CodeQualityMetrics codeQualityMetrics) {
        this.codeQualityMetrics = codeQualityMetrics;
    }

    public List<DatabaseChange> getDatabaseChanges() {
        return databaseChanges;
    }

    public void setDatabaseChanges(List<DatabaseChange> databaseChanges) {
        this.databaseChanges = databaseChanges;
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