package com.analyzer.model;

public class DatabaseChange {
    private String type;
    private String description;
    private String author;
    private String id;

    public DatabaseChange(String type, String description, String author, String id) {
        this.type = type;
        this.description = description;
        this.author = author;
        this.id = id;
    }

    // Getters and setters

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    @Override
    public String toString() {
        return "DatabaseChange{" +
                "type='" + type + '\'' +
                ", description='" + description + '\'' +
                ", author='" + author + '\'' +
                ", id='" + id + '\'' +
                '}';
    }
}