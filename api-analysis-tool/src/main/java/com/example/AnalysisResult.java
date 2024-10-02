package com.example;

import java.util.List;

import com.example.model.APIInfo;

public class AnalysisResult {
  private APIInfo apiInventory;
  private List<DatabaseChangelogScanner.DatabaseChange> databaseChanges;

  public AnalysisResult(APIInfo apiInventory, List<DatabaseChangelogScanner.DatabaseChange> databaseChanges) {
    this.apiInventory = apiInventory;
    this.databaseChanges = databaseChanges;
  }

  // Getters and setters for all fields

  public APIInfo getApiInventory() {
    return apiInventory;
  }

  public void setApiInventory(APIInfo apiInventory) {
    this.apiInventory = apiInventory;
  }

  public List<DatabaseChangelogScanner.DatabaseChange> getDatabaseChanges() {
    return databaseChanges;
  }

  public void setDatabaseChanges(List<DatabaseChangelogScanner.DatabaseChange> databaseChanges) {
    this.databaseChanges = databaseChanges;
  }

}
