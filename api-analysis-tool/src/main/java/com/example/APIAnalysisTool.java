package com.example;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class APIAnalysisTool {
  private static final Logger logger = LoggerFactory.getLogger(APIAnalysisTool.class);

  private Map<String, AnalysisResult> projectResults = new HashMap<>();
  private final SequenceDiagramGenerator sequenceDiagramGenerator;
  private final ClassDiagramGenerator classDiagramGenerator;
  private final ComponentDiagramGenerator componentDiagramGenerator;
  private final StateDiagramGenerator stateDiagramGenerator;
  private final ActivityDiagramGenerator activityDiagramGenerator;
  private final APIInventoryExtractor apiInventoryExtractor;
  private final DesignComparator designComparator;
  private final CSVGenerator csvGenerator;
  private final DocumentationGenerator documentationGenerator;
  private final ExternalCallScanner externalCallScanner;

  private String latestSequenceDiagram;
  private String latestClassDiagram;
  private String latestComponentDiagram;
  private String latestStateDiagram;
  private String latestActivityDiagram;
  private ComparisonResult latestComparisonResult;
  private List<APIInfo> latestAPIInventory;
  private List<ExternalCallInfo> latestExternalCalls;
  private String latestCsvPath;
  private String latestDocumentationPath;
  private String latestHtmlDiffPath;

  public APIAnalysisTool() {
    this.sequenceDiagramGenerator = new SequenceDiagramGenerator();
    this.classDiagramGenerator = new ClassDiagramGenerator();
    this.componentDiagramGenerator = new ComponentDiagramGenerator();
    this.stateDiagramGenerator = new StateDiagramGenerator();
    this.activityDiagramGenerator = new ActivityDiagramGenerator();
    this.apiInventoryExtractor = new APIInventoryExtractor();
    this.designComparator = new DesignComparator();
    this.csvGenerator = new CSVGenerator();
    this.documentationGenerator = new DocumentationGenerator();
    this.externalCallScanner = new ExternalCallScanner();
  }

  public AnalysisResult analyzeProject(String projectPath, String existingDesignPath, String projectName)
      throws IOException {
    logger.info("Starting project analysis for: {}", projectPath);
    List<ClassNode> allClasses = parseJavaClasses(projectPath);

    latestSequenceDiagram = sequenceDiagramGenerator.generateSequenceDiagram(allClasses);
    latestClassDiagram = classDiagramGenerator.generateClassDiagram(allClasses);
    latestComponentDiagram = componentDiagramGenerator.generateComponentDiagram(allClasses);
    latestStateDiagram = stateDiagramGenerator.generateStateDiagram(allClasses);
    latestActivityDiagram = activityDiagramGenerator.generateActivityDiagram(allClasses);

    latestAPIInventory = apiInventoryExtractor.extractAPIs(allClasses);
    latestExternalCalls = externalCallScanner.findExternalCalls(allClasses);

    String existingDiagram = readExistingDesign(existingDesignPath);
    latestComparisonResult = designComparator.compare(latestClassDiagram, existingDiagram, projectName);

    latestCsvPath = generateCSV(projectName);
    latestDocumentationPath = generateDocumentation(projectName);
    latestHtmlDiffPath = generateHtmlDiff(projectName);

    saveDiagrams(projectName);

    logger.info("Project analysis completed successfully");
    return new AnalysisResult(latestSequenceDiagram, latestClassDiagram, latestComponentDiagram,
        latestStateDiagram, latestActivityDiagram, latestComparisonResult, latestAPIInventory,
        latestExternalCalls, latestCsvPath, latestDocumentationPath, latestHtmlDiffPath);
  }

  private void saveDiagrams(String projectName) {
    saveDiagramToFile(latestSequenceDiagram, "sequence_" + projectName);
    saveDiagramToFile(latestClassDiagram, "class_" + projectName);
    saveDiagramToFile(latestComponentDiagram, "component_" + projectName);
    saveDiagramToFile(latestStateDiagram, "state_" + projectName);
    saveDiagramToFile(latestActivityDiagram, "activity_" + projectName);
  }

  public Map<String, AnalysisResult> analyzeProjects(List<MultipartFile> projectFiles) throws IOException {
    for (MultipartFile projectFile : projectFiles) {
      String projectName = getProjectName(projectFile.getOriginalFilename());
      Path projectPath = extractZipFile(projectFile);
      AnalysisResult result = analyzeProject(projectPath.toString(), null, projectName);
      projectResults.put(projectName, result);
      Files.deleteIfExists(projectPath);
    }
    return projectResults;
  }

  private String getProjectName(String filename) {
    return filename.substring(0, filename.lastIndexOf('.'));
  }

  private Path extractZipFile(MultipartFile zipFile) throws IOException {
    Path tempDir = Files.createTempDirectory("project_");
    try (ZipInputStream zis = new ZipInputStream(zipFile.getInputStream())) {
      ZipEntry entry;
      while ((entry = zis.getNextEntry()) != null) {
        Path filePath = tempDir.resolve(entry.getName());
        if (!entry.isDirectory()) {
          Files.createDirectories(filePath.getParent());
          Files.copy(zis, filePath);
        }
      }
    }
    return tempDir;
  }

  public String generateCombinedDiagram(String diagramType) {
    List<String> diagrams = projectResults.values().stream()
        .map(result -> switch (diagramType) {
          case "sequence" -> result.getSequenceDiagram();
          case "class" -> result.getClassDiagram();
          case "component" -> result.getComponentDiagram();
          case "state" -> result.getStateDiagram();
          case "activity" -> result.getActivityDiagram();
          default -> "";
        })
        .toList();

    return switch (diagramType) {
      case "sequence" -> sequenceDiagramGenerator.combineDiagrams(diagrams);
      case "class" -> classDiagramGenerator.combineDiagrams(diagrams);
      case "component" -> componentDiagramGenerator.combineDiagrams(diagrams);
      case "state" -> stateDiagramGenerator.combineDiagrams(diagrams);
      case "activity" -> activityDiagramGenerator.combineDiagrams(diagrams);
      default -> "";
    };
  }

  private void saveDiagramToFile(String diagram, String prefix) {
    try {
      String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
      String fileName = prefix + "_" + timestamp + ".puml";
      Path filePath = Paths.get("diagrams", fileName);
      Files.createDirectories(filePath.getParent());
      Files.writeString(filePath, diagram);
      logger.info("Diagram saved to: {}", filePath.toAbsolutePath());
    } catch (IOException e) {
      logger.error("Error saving diagram: {}", e.getMessage());
    }
  }

  private String generateCSV(String projectName) {
    String csvPath = "apm_import_" + projectName + ".csv";
    csvGenerator.generateCSV(latestAPIInventory, latestExternalCalls, csvPath, "default");
    return csvPath;
  }

  private String generateDocumentation(String projectName) {
    String docPath = "api_documentation_" + projectName + ".md";
    documentationGenerator.generateDocumentation(latestAPIInventory, latestExternalCalls, docPath);
    return docPath;
  }

  private String generateHtmlDiff(String projectName) {
    String htmlDiffPath = "diagram_diff_" + projectName + ".html";
    String htmlDiff = designComparator.generateHtmlDiff(latestComparisonResult);
    try {
      Files.writeString(Paths.get(htmlDiffPath), htmlDiff);
      logger.info("HTML diff saved to: {}", htmlDiffPath);
    } catch (IOException e) {
      logger.error("Error saving HTML diff: {}", e.getMessage());
    }
    return htmlDiffPath;
  }

  private List<ClassNode> parseJavaClasses(String projectPath) throws IOException {
    List<ClassNode> allClasses = new ArrayList<>();
    try (JarFile jarFile = new JarFile(projectPath)) {
      Enumeration<JarEntry> entries = jarFile.entries();
      while (entries.hasMoreElements()) {
        JarEntry entry = entries.nextElement();
        if (entry.getName().endsWith(".class")) {
          try (InputStream is = jarFile.getInputStream(entry)) {
            ClassReader reader = new ClassReader(is);
            ClassNode classNode = new ClassNode();
            reader.accept(classNode, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            allClasses.add(classNode);
          }
        }
      }
    }
    return allClasses;
  }

  private ClassNode parseClassFile(byte[] classBytes) {
    try {
      ClassReader reader = new ClassReader(classBytes);
      ClassNode classNode = new ClassNode();
      reader.accept(classNode, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
      return classNode;
    } catch (Exception e) {
      logger.error("Error parsing class file: " + e.getMessage(), e);
      return null;
    }
  }

  private void parseJarFile(String jarPath, List<ClassNode> allClasses) throws IOException {
    try (JarFile jarFile = new JarFile(jarPath)) {
      Enumeration<JarEntry> entries = jarFile.entries();
      while (entries.hasMoreElements()) {
        JarEntry entry = entries.nextElement();
        if (entry.getName().endsWith(".class")) {
          try (InputStream is = jarFile.getInputStream(entry)) {
            ClassReader reader = new ClassReader(is);
            ClassNode classNode = new ClassNode();
            reader.accept(classNode, 0);
            allClasses.add(classNode);
          }
        }
      }
    }
  }

  private String readExistingDesign(String existingDesignPath) throws IOException {
    return Files.readString(Paths.get(existingDesignPath));
  }

  // Getter methods for latest results
  public String getLatestSequenceDiagram() {
    return latestSequenceDiagram;
  }

  public String getLatestClassDiagram() {
    return latestClassDiagram;
  }

  public String getLatestComponentDiagram() {
    return latestComponentDiagram;
  }

  public String getLatestStateDiagram() {
    return latestStateDiagram;
  }

  public String getLatestActivityDiagram() {
    return latestActivityDiagram;
  }

  public ComparisonResult getLatestComparisonResult() {
    return latestComparisonResult;
  }

  public List<APIInfo> getLatestAPIInventory() {
    return latestAPIInventory;
  }

  public List<ExternalCallInfo> getLatestExternalCalls() {
    return latestExternalCalls;
  }

  public String getLatestCsvPath() {
    return latestCsvPath;
  }

  public String getLatestDocumentationPath() {
    return latestDocumentationPath;
  }

  public String getLatestHtmlDiffPath() {
    return latestHtmlDiffPath;
  }
}