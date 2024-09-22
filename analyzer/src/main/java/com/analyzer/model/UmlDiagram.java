package com.analyzer.model;

public class UmlDiagram {
    private String id;
    private String type;
    private String content;

    public UmlDiagram(String id, String type, String content) {
        this.id = id;
        this.type = type;
        this.content = content;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}