package com.analyzer.model;

public class CodeQualityMetrics {
    private int violations;
    private double complexity;
    private double duplication;

    public CodeQualityMetrics(int violations, double complexity, double duplication) {
        this.violations = violations;
        this.complexity = complexity;
        this.duplication = duplication;
    }

    public int getViolations() {
        return violations;
    }

    public void setViolations(int violations) {
        this.violations = violations;
    }

    public double getComplexity() {
        return complexity;
    }

    public void setComplexity(double complexity) {
        this.complexity = complexity;
    }

    public double getDuplication() {
        return duplication;
    }

    public void setDuplication(double duplication) {
        this.duplication = duplication;
    }
}