package com.example;

import java.util.ArrayList;
import java.util.List;

import com.example.model.APIInfo;

import liquibase.changelog.ChangeSet;

public class AnalysisResult {
  private APIInfo apiInventory;
  private List<ChangeSet> changeSets;
  private String sequenceDiagram;

  public String getSequenceDiagram() {
    return sequenceDiagram;
  }

  public void setSequenceDiagram(String sequenceDiagram) {
    this.sequenceDiagram = sequenceDiagram;
  }

  public AnalysisResult(APIInfo apiInventory) {
    this.apiInventory = apiInventory;
    this.changeSets = new ArrayList<>();
  }

  // Getters and setters for all fields

  public APIInfo getApiInventory() {
    return apiInventory;
  }

  public void setApiInventory(APIInfo apiInventory) {
    this.apiInventory = apiInventory;
  }

  public List<ChangeSet> getChangeSets() {
    return changeSets;
  }

  public void setDatabaseChanges(List<ChangeSet> changeSets) {
    this.changeSets = changeSets;
  }

}
