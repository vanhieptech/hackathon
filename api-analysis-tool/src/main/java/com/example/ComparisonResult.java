package com.example;

import java.util.List;
import java.util.Map;

public class ComparisonResult {
    private List<DiffResult> diffs;
    private double matchingScore;
    private double structuralSimilarity;
    private Map<String, List<String>> elementRelations;
    private String projectName;

    // Getters and setters for all fields

    public List<DiffResult> getDiffs() { return diffs; }
    public void setDiffs(List<DiffResult> diffs) { this.diffs = diffs; }

    public double getMatchingScore() { return matchingScore; }
    public void setMatchingScore(double matchingScore) { this.matchingScore = matchingScore; }

    public double getStructuralSimilarity() { return structuralSimilarity; }
    public void setStructuralSimilarity(double structuralSimilarity) { this.structuralSimilarity = structuralSimilarity; }

    public Map<String, List<String>> getElementRelations() { return elementRelations; }
    public void setElementRelations(Map<String, List<String>> elementRelations) { this.elementRelations = elementRelations; }

    public String getProjectName() { return projectName; }
    public void setProjectName(String projectName) { this.projectName = projectName; }
}