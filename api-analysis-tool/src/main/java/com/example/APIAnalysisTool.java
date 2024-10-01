package com.example;

import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Service
public class APIAnalysisTool {
  private static final Logger logger = LoggerFactory.getLogger(APIAnalysisTool.class);

  @Autowired
  private ProjectScanner projectScanner;

  @Autowired
  private ExternalCallScanner externalCallScanner;

  @Autowired
  private DatabaseChangelogScanner databaseChangelogScanner;

  public Map<String, AnalysisResult> analyzeMultipleProjects(List<MultipartFile> projectFiles) throws IOException {
    Map<String, AnalysisResult> results = new ConcurrentHashMap<>();
    ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

    List<CompletableFuture<Void>> futures = projectFiles.stream()
        .map(file -> CompletableFuture.runAsync(() -> {
          try {
            AnalysisResult result = analyzeProject(file);
            results.put(file.getOriginalFilename(), result);
          } catch (IOException e) {
            logger.error("Error analyzing project: " + file.getOriginalFilename(), e);
          }
        }, executor))
        .collect(Collectors.toList());

    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    executor.shutdown();

    return results;
  }

  private AnalysisResult analyzeProject(MultipartFile projectFile) throws IOException {
    String projectName = projectFile.getOriginalFilename();
    logger.info("Starting project analysis for: {}", projectName);

    Path tempDir = Files.createTempDirectory("project_analysis");
    Path projectPath = tempDir.resolve(projectName);
    projectFile.transferTo(projectPath.toFile());

    try {
      Map<String, String> configProperties = projectScanner.loadProjectConfigProperties(projectPath);
      List<ClassNode> allClasses = projectScanner.parseJavaClasses(projectPath);

      APIInventoryExtractor extractor = new APIInventoryExtractor(configProperties, projectName, allClasses);
      APIInfo apiInfo = extractor.extractExposedAPIs();
      List<ExternalCallInfo> externalCalls = externalCallScanner.findExternalCalls(allClasses);
      List<DatabaseChangelogScanner.DatabaseChange> databaseChanges = databaseChangelogScanner
          .scanChangelog(projectPath.toString());

      return new AnalysisResult(apiInfo, externalCalls, databaseChanges);
    } finally {
      Files.deleteIfExists(projectPath);
      Files.deleteIfExists(tempDir);
    }
  }
}