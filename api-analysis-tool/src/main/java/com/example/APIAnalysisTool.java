package com.example;

import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.yaml.snakeyaml.Yaml;

import com.example.model.APIInfo;

import liquibase.changelog.ChangeSet;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class APIAnalysisTool {
  private static final Logger logger = LoggerFactory.getLogger(APIAnalysisTool.class);

  @Autowired
  private ProjectScanner projectScanner;

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
          } catch (Exception e) {
            logger.error(e.getMessage());
          }
        }, executor))
        .collect(Collectors.toList());

    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    executor.shutdown();

    return results;
  }

  private AnalysisResult analyzeProject(MultipartFile projectFile) throws Exception {
    String projectName = projectFile.getOriginalFilename();
    logger.info("Starting project analysis for: {}", projectName);

    Path tempDir = Files.createTempDirectory("project_analysis");
    Path projectPath = tempDir.resolve(projectName);
    projectFile.transferTo(projectPath.toFile());

    try {
      Map<String, String> configProperties = projectScanner.loadProjectConfigProperties(projectPath.toString());
      List<ClassNode> allClasses = projectScanner.parseJavaClasses(projectPath);

      APIAnalyzer analyzer = new APIAnalyzer(configProperties, projectName, allClasses);
      APIInfo apiInfo = analyzer.analyzeAPI();
      // List<DatabaseChangelogScanner.DatabaseChange> databaseChanges =
      // databaseChangelogScanner
      // .scanChangelog(projectPath.toString());
      // LiquibaseChangeScanner scanner = new LiquibaseChangeScanner();
      // List<LiquibaseChangeScanner.ChangeSetInfo> changeSets =
      // scanner.scanJarForDatabaseChanges(projectPath.toString(), configProperties);
      // scanner.printChangeSetSummary(changeSets);
      return new AnalysisResult(apiInfo);
    } finally {
      Files.deleteIfExists(projectPath);
      Files.deleteIfExists(tempDir);
    }
  }

  public String generateSequenceDiagram(List<APIInfo> apiInfoList, SequenceDiagramGenerator.DiagramOptions options) {
    SequenceDiagramGenerator generator = new SequenceDiagramGenerator();
    return generator.generateSequenceDiagram(apiInfoList, options);
  }
}