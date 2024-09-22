package com.analyzer.model;

public enum AnalysisStatus {
    NOT_FOUND,
    IN_PROGRESS,
    PARSING_FILES,
    GENERATING_UML,
    COMPARING_RESULTS,
    COMPLETED,
    ERROR
}