package com.example;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.example.LiquibaseChangeScanner.ChangeSetInfo;
import com.example.model.APIInfo;

import liquibase.changelog.ChangeSet;

public class AnalysisResult {
  private APIInfo apiInventory;
  private List<ChangeSetInfo> changeSets;
  private String sequenceDiagram;
  private List<Map<String, String>> exposedAPIs;

  public List<Map<String, String>> getExposedAPIs() {
    return exposedAPIs;
  }

  public void setExposedAPIs(List<Map<String, String>> exposedAPIs) {
    this.exposedAPIs = exposedAPIs;
  }

  public String getSequenceDiagram() {
    return sequenceDiagram;
  }

  public void setSequenceDiagram(String sequenceDiagram) {
    this.sequenceDiagram = sequenceDiagram;
  }

  public AnalysisResult(APIInfo apiInventory, List<Map<String, String>> exposedAPIs) {
    this.apiInventory = apiInventory;
    this.changeSets = new ArrayList<>();
    this.exposedAPIs = exposedAPIs;
  }

  // Getters and setters for all fields

  public APIInfo getApiInventory() {
    return apiInventory;
  }

  public void setApiInventory(APIInfo apiInventory) {
    this.apiInventory = apiInventory;
  }

  public List<ChangeSetInfo> getChangeSets() {
    return changeSets;
  }

  public void setDatabaseChanges(List<ChangeSetInfo> changeSets) {
    this.changeSets = changeSets;
  }

}
