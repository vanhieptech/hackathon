package com.example;

import java.util.List;
import java.util.Map;

public class AnalysisResult {
  private APIInfo apiInventory;
  private List<ExternalCallInfo> externalCalls;
  private List<DatabaseChangelogScanner.DatabaseChange> databaseChanges;

  public AnalysisResult(APIInfo apiInventory, List<ExternalCallInfo> externalCalls,
      List<DatabaseChangelogScanner.DatabaseChange> databaseChanges) {
    this.apiInventory = apiInventory;
    this.externalCalls = externalCalls;
    this.databaseChanges = databaseChanges;
  }

  // Getters and setters for all fields

  public APIInfo getApiInventory() {
    return apiInventory;
  }

  public void setApiInventory(APIInfo apiInventory) {
    this.apiInventory = apiInventory;
  }

  public List<ExternalCallInfo> getExternalCalls() {
    return externalCalls;
  }

  public void setExternalCalls(List<ExternalCallInfo> externalCalls) {
    this.externalCalls = externalCalls;
  }

  public List<DatabaseChangelogScanner.DatabaseChange> getDatabaseChanges() {
    return databaseChanges;
  }
}