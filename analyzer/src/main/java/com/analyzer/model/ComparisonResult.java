package com.analyzer.model;

import java.util.List;
import java.util.Map;

public class ComparisonResult {
    private double overallScore;
    private List<String> discrepancies;
    private Map<String, Double> detailedScores;

    public ComparisonResult(double overallScore, List<String> discrepancies, Map<String, Double> detailedScores) {
        this.overallScore = overallScore;
        this.discrepancies = discrepancies;
        this.detailedScores = detailedScores;
    }

    public double getOverallScore() {
        return overallScore;
    }

    public void setOverallScore(double overallScore) {
        this.overallScore = overallScore;
    }

    public List<String> getDiscrepancies() {
        return discrepancies;
    }

    public void setDiscrepancies(List<String> discrepancies) {
        this.discrepancies = discrepancies;
    }

    public Map<String, Double> getDetailedScores() {
        return detailedScores;
    }

    public void setDetailedScores(Map<String, Double> detailedScores) {
        this.detailedScores = detailedScores;
    }
}