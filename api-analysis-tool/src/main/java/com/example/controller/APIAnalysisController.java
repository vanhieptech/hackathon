package com.example.controller;

import com.example.*;
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

      Map<String, Object> response = new HashMap<>();
      for (Map.Entry<String, AnalysisResult> entry : results.entrySet()) {
        String projectName = entry.getKey();
        AnalysisResult result = entry.getValue();
        result.setSequenceDiagram(sequenceDiagram);
        response.put(projectName, createProjectResponse(result));
      }

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
    return response;
  }
}