package com.example;

public class DiffResult {
    private DiffType type;
    private String content;

    public DiffResult(DiffType type, String content) {
        this.type = type;
        this.content = content;
    }

    // Getters and setters
    public DiffType getType() { return type; }
    public void setType(DiffType type) { this.type = type; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
}