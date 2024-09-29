package com.example.controller;

import com.example.*;
import com.example.service.PlantUMLService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/analysis")
public class APIAnalysisController {

  @Autowired
  private APIAnalysisTool apiAnalysisTool;

  @Autowired
  private PlantUMLService plantUMLService;

  @PostMapping("/analyze-multiple")
  public ResponseEntity<?> analyzeMultipleProjects(@RequestParam("projectFiles") List<MultipartFile> projectFiles) {
    try {
      Map<String, AnalysisResult> results = apiAnalysisTool.analyzeProjects(projectFiles);
      Map<String, Object> response = new HashMap<>();

      for (Map.Entry<String, AnalysisResult> entry : results.entrySet()) {
        String projectName = entry.getKey();
        AnalysisResult result = entry.getValue();
        response.put(projectName, createProjectResponse(result));
      }

      // Add combined diagrams
      response.put("combinedSequenceDiagram",
          plantUMLService.encodeDiagram(apiAnalysisTool.generateCombinedDiagram("sequence")));
      response.put("combinedClassDiagram",
          plantUMLService.encodeDiagram(apiAnalysisTool.generateCombinedDiagram("class")));
      response.put("combinedComponentDiagram",
          plantUMLService.encodeDiagram(apiAnalysisTool.generateCombinedDiagram("component")));
      response.put("combinedStateDiagram",
          plantUMLService.encodeDiagram(apiAnalysisTool.generateCombinedDiagram("state")));
      response.put("combinedActivityDiagram",
          plantUMLService.encodeDiagram(apiAnalysisTool.generateCombinedDiagram("activity")));

      return ResponseEntity.ok(response);
    } catch (IOException e) {
      return ResponseEntity.badRequest().body("Error analyzing projects: " + e.getMessage());
    }
  }

  @PostMapping("/analyze")
  public ResponseEntity<?> analyzeProject(@RequestParam("projectFile") MultipartFile projectFile,
      @RequestParam("designFile") MultipartFile designFile) {
    try {
      String projectName = getProjectName(projectFile.getOriginalFilename());
      Path projectPath = Files.createTempFile("project", ".jar");
      Path designPath = Files.createTempFile("design", ".puml");

      projectFile.transferTo(projectPath);
      designFile.transferTo(designPath);

      AnalysisResult result = apiAnalysisTool.analyzeProject(projectPath.toString(), designPath.toString(),
          projectName);
      Map<String, Object> response = createProjectResponse(result);

      // Clean up temporary files
      Files.deleteIfExists(projectPath);
      Files.deleteIfExists(designPath);

      return ResponseEntity.ok(response);
    } catch (IOException e) {
      return ResponseEntity.badRequest().body("Error analyzing project: " + e.getMessage());
    }
  }

  private String getProjectName(String filename) {
    return filename.substring(0, filename.lastIndexOf('.'));
  }

  private Map<String, Object> createProjectResponse(AnalysisResult result) {
    Map<String, Object> response = new HashMap<>();
    response.put("sequenceDiagramUrl", plantUMLService.encodeDiagram(result.getSequenceDiagram()));
    response.put("classDiagramUrl", plantUMLService.encodeDiagram(result.getClassDiagram()));
    response.put("componentDiagramUrl", plantUMLService.encodeDiagram(result.getComponentDiagram()));
    response.put("stateDiagramUrl", plantUMLService.encodeDiagram(result.getStateDiagram()));
    response.put("activityDiagramUrl", plantUMLService.encodeDiagram(result.getActivityDiagram()));
    response.put("apiInventory", result.getApiInventory());
    response.put("externalCalls", result.getExternalCalls());
    response.put("comparisonResult", result.getComparisonResult());
    response.put("csvPath", result.getCsvPath());
    response.put("documentationPath", result.getDocumentationPath());
    response.put("htmlDiffPath", result.getHtmlDiffPath());
    return response;
  }

  @GetMapping("/diagram/{type}")
  public ResponseEntity<String> getDiagram(@PathVariable String type) {
    String diagram = switch (type) {
      case "sequence" -> apiAnalysisTool.getLatestSequenceDiagram();
      case "class" -> apiAnalysisTool.getLatestClassDiagram();
      case "component" -> apiAnalysisTool.getLatestComponentDiagram();
      case "state" -> apiAnalysisTool.getLatestStateDiagram();
      case "activity" -> apiAnalysisTool.getLatestActivityDiagram();
      default -> null;
    };
    return diagram != null ? ResponseEntity.ok(diagram) : ResponseEntity.notFound().build();
  }

  @GetMapping("/comparison")
  public ResponseEntity<ComparisonResult> getComparisonResult() {
    ComparisonResult result = apiAnalysisTool.getLatestComparisonResult();
    return ResponseEntity.ok(result);
  }

  @GetMapping("/api-inventory")
  public ResponseEntity<List<APIInfo>> getAPIInventory() {
    List<APIInfo> inventory = apiAnalysisTool.getLatestAPIInventory();
    return ResponseEntity.ok(inventory);
  }

  @GetMapping("/external-calls")
  public ResponseEntity<List<ExternalCallInfo>> getExternalCalls() {
    List<ExternalCallInfo> externalCalls = apiAnalysisTool.getLatestExternalCalls();
    return ResponseEntity.ok(externalCalls);
  }

  @GetMapping("/download/{fileType}")
  public ResponseEntity<ByteArrayResource> downloadFile(@PathVariable String fileType) {
    try {
      String filePath = switch (fileType) {
        case "csv" -> apiAnalysisTool.getLatestCsvPath();
        case "documentation" -> apiAnalysisTool.getLatestDocumentationPath();
        case "htmlDiff" -> apiAnalysisTool.getLatestHtmlDiffPath();
        default -> null;
      };

      if (filePath == null) {
        return ResponseEntity.notFound().build();
      }

      byte[] data = Files.readAllBytes(Path.of(filePath));
      ByteArrayResource resource = new ByteArrayResource(data);

      return ResponseEntity.ok()
          .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + getFileName(fileType))
          .contentType(MediaType.APPLICATION_OCTET_STREAM)
          .contentLength(data.length)
          .body(resource);
    } catch (IOException e) {
      return ResponseEntity.internalServerError().build();
    }
  }

  private String getFileExtension(String filename) {
    int lastDotIndex = filename.lastIndexOf('.');
    return lastDotIndex == -1 ? "" : filename.substring(lastDotIndex);
  }

  private String getFileName(String fileType) {
    return switch (fileType) {
      case "csv" -> "api_inventory.csv";
      case "documentation" -> "api_documentation.md";
      case "htmlDiff" -> "design_comparison.html";
      default -> "unknown_file";
    };
  }

  @GetMapping("/database-changelogs")
  public ResponseEntity<List<DatabaseChangelogScanner.DatabaseChange>> getDatabaseChangeLogs() {
    List<DatabaseChangelogScanner.DatabaseChange> changeLogs = apiAnalysisTool.getLatestDatabaseChanges();
    return ResponseEntity.ok(changeLogs);
  }
}