package com.example.controller;

import com.example.*;
import com.example.LiquibaseChangeScanner.ChangeSetInfo;
import com.example.model.APIInfo;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/analysis")
public class APIAnalysisController {

  @Autowired
  private APIAnalysisTool apiAnalysisTool;

  @PostMapping("/analyze-multiple")
  public ResponseEntity<?> analyzeMultipleProjects(@RequestParam("projectFiles") List<MultipartFile> projectFiles) {
    try {
      Map<String, AnalysisResult> results = apiAnalysisTool.analyzeMultipleProjects(projectFiles);
      List<APIInfo> allApiInfo = results.values().stream()
          .map(AnalysisResult::getApiInventory)
          .collect(Collectors.toList());

      SequenceDiagramGenerator.DiagramOptions options = new SequenceDiagramGenerator.DiagramOptions(true, true, true);
      String sequenceDiagram = apiAnalysisTool.generateSequenceDiagram(allApiInfo, options);
      // String activityDiagram = apiAnalysisTool.generateActivityDiagram(allApiInfo);
      // String classDiagram = apiAnalysisTool.generateClassDiagram(allApiInfo);
      var exposedAPIs = results.values().stream()
          .flatMap(result -> result.getExposedAPIs().stream())
          .collect(Collectors.toList());
      Map<String, Object> response = new HashMap<>();
      response.put("sequenceDiagram", sequenceDiagram);
      response.put("exposedAPIs", exposedAPIs);
      // response.put("liquibaseChanges", liquibaseChanges);
      return ResponseEntity.ok(response);
    } catch (IOException e) {
      return ResponseEntity.badRequest().body("Error analyzing projects: " + e.getMessage());
    }
  }

  private Map<String, Object> createProjectResponse(AnalysisResult result) {
    Map<String, Object> response = new HashMap<>();
    response.put("apiInventory", result.getApiInventory());
    response.put("databaseChanges", result.getChangeSets());
    response.put("sequenceDiagram", result.getSequenceDiagram());
    response.put("exposedAPIs", result.getExposedAPIs());
    return response;
  }
}