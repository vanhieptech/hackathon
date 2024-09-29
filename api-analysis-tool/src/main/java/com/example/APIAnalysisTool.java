package com.example;

import liquibase.changelog.ChangeSet;
import liquibase.changelog.DatabaseChangeLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.yaml.snakeyaml.Yaml;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class APIAnalysisTool {
  private static final Logger logger = LoggerFactory.getLogger(APIAnalysisTool.class);

  private Map<String, AnalysisResult> projectResults = new HashMap<>();
  private final ClassDiagramGenerator classDiagramGenerator;
  private final ComponentDiagramGenerator componentDiagramGenerator;
  private final StateDiagramGenerator stateDiagramGenerator;
  private final ActivityDiagramGenerator activityDiagramGenerator;
  private final ServiceInventoryExtractor serviceInventoryExtractor;
  private final DesignComparator designComparator;
  private final CSVGenerator csvGenerator;
  private final DocumentationGenerator documentationGenerator;
  private final DatabaseChangelogScanner databaseChangelogScanner;

  private String latestSequenceDiagram;
  private String latestClassDiagram;
  private String latestComponentDiagram;
  private String latestStateDiagram;
  private String latestActivityDiagram;
  private List<APIInfo> latestExposedAPIInventory;
  private String latestUtilizedServiceInventory;
  private ComparisonResult latestComparisonResult;
  private List<APIInfo> latestAPIInventory;
  private List<ExternalCallInfo> latestExternalCalls;
  private String latestCsvPath;
  private String latestDocumentationPath;
  private String latestHtmlDiffPath;
  private List<DatabaseChangelogScanner.DatabaseChange> latestDatabaseChanges;

  public APIAnalysisTool() {
    this.classDiagramGenerator = new ClassDiagramGenerator();
    this.componentDiagramGenerator = new ComponentDiagramGenerator();
    this.stateDiagramGenerator = new StateDiagramGenerator();
    this.activityDiagramGenerator = new ActivityDiagramGenerator();
    this.serviceInventoryExtractor = new ServiceInventoryExtractor();
    this.designComparator = new DesignComparator();
    this.csvGenerator = new CSVGenerator();
    this.documentationGenerator = new DocumentationGenerator();
    // this.externalCallScanner = new ExternalCallScanner();
    this.databaseChangelogScanner = new DatabaseChangelogScanner();
  }

  public AnalysisResult analyzeProject(String projectPath, String existingDesignPath, String projectName)
      throws IOException {
    logger.info("Starting project analysis for: {}", projectPath);
    // Load configuration properties from the project
    Map<String, String> configProperties = loadProjectConfigProperties(projectPath);

    List<ClassNode> allClasses = parseJavaClasses(projectPath);

    SourceCodeAnalyzer sourceCodeAnalyzer = new SourceCodeAnalyzer();
    Map<String, ClassInfo> sourceCodeInfo = sourceCodeAnalyzer.analyzeSourceCode(projectPath);
    Map<String, Set<String>> classImports = sourceCodeAnalyzer.extractClassImports(sourceCodeInfo);

    ExternalCallScanner scanner = new ExternalCallScanner(configProperties, classImports);
    List<ExternalCallInfo> externalCalls = scanner.findExternalCalls(allClasses);
    // Update the SequenceDiagramGenerator constructor if necessary
    SequenceDiagramGenerator sequenceDiagramGenerator = new SequenceDiagramGenerator(configProperties, classImports);

    // Generate the sequence diagram
    latestSequenceDiagram = sequenceDiagramGenerator.generateSequenceDiagram(allClasses);
    latestClassDiagram = classDiagramGenerator.generateClassDiagram(allClasses, sourceCodeInfo);
    latestComponentDiagram = componentDiagramGenerator.generateComponentDiagram(allClasses, sourceCodeInfo);
    latestStateDiagram = stateDiagramGenerator.generateStateDiagram(allClasses);
    latestActivityDiagram = activityDiagramGenerator.generateActivityDiagram(allClasses);

    APIInventoryExtractor apiInventoryExtractor = new APIInventoryExtractor(configProperties, projectName);
    latestExposedAPIInventory = apiInventoryExtractor.extractExposedAPIs(allClasses);
    latestUtilizedServiceInventory = serviceInventoryExtractor.extractUtilizedServices(allClasses, configProperties);
    latestExternalCalls = externalCalls;

    String existingDiagram = readExistingDesign(existingDesignPath);
    latestComparisonResult = designComparator.compare(latestClassDiagram, existingDiagram, projectName);

    latestAPIInventory = latestExposedAPIInventory;
    latestCsvPath = generateCSV(projectName);
    latestDocumentationPath = generateDocumentation(projectName);
    latestHtmlDiffPath = generateHtmlDiff(projectName);

    // Add database changelog scanning
    latestDatabaseChanges = scanDatabaseChangeLogs(projectPath);

    saveDiagrams(projectName);

    logger.info("Project analysis completed successfully");
    return new AnalysisResult(latestSequenceDiagram, latestClassDiagram, latestComponentDiagram,
        latestStateDiagram, latestActivityDiagram, latestComparisonResult, latestExposedAPIInventory,
        latestUtilizedServiceInventory, latestExternalCalls, latestCsvPath, latestDocumentationPath,
        latestHtmlDiffPath, latestDatabaseChanges);
  }

  private Map<String, String> loadProjectConfigProperties(String projectPath) throws IOException {
    logger.info("Loading configuration properties from project: {}", projectPath);
    Map<String, String> configProperties = new HashMap<>();

    if (Files.isDirectory(Paths.get(projectPath))) {
      loadFromDirectory(projectPath, configProperties);
    } else if (projectPath.endsWith(".jar")) {
      loadFromJar(projectPath, configProperties);
    } else if (projectPath.endsWith(".zip")) {
      loadFromZip(projectPath, configProperties);
    } else {
      logger.warn("Unsupported project type: {}", projectPath);
    }

    logger.info("Loaded {} configuration properties", configProperties.size());
    return configProperties;
  }

  private void loadFromDirectory(String projectPath, Map<String, String> configProperties) throws IOException {
    Path propertiesPath = Paths.get(projectPath, "src", "main", "resources", "application.properties");
    Path yamlPath = Paths.get(projectPath, "src", "main", "resources", "application.yml");

    if (Files.exists(propertiesPath)) {
      loadProperties(Files.newInputStream(propertiesPath), configProperties);
    }
    if (Files.exists(yamlPath)) {
      loadYaml(Files.newInputStream(yamlPath), configProperties);
    }
  }

  private void loadFromJar(String jarPath, Map<String, String> configProperties) throws IOException {
    try (JarFile jarFile = new JarFile(jarPath)) {
      JarEntry propertiesEntry = jarFile.getJarEntry("BOOT-INF/classes/application.properties");
      if (propertiesEntry != null) {
        loadProperties(jarFile.getInputStream(propertiesEntry), configProperties);
      }

      JarEntry yamlEntry = jarFile.getJarEntry("BOOT-INF/classes/application.yml");
      if (yamlEntry != null) {
        loadYaml(jarFile.getInputStream(yamlEntry), configProperties);
      }

      JarEntry bootStrapYamlEntry = jarFile.getJarEntry("BOOT-INF/classes/bootstrap.yml");
      if (bootStrapYamlEntry != null) {
        loadYaml(jarFile.getInputStream(bootStrapYamlEntry), configProperties);
      }
    }
  }

  private void loadFromZip(String zipPath, Map<String, String> configProperties) throws IOException {
    try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipPath))) {
      ZipEntry entry;
      while ((entry = zis.getNextEntry()) != null) {
        if (entry.getName().endsWith("application.properties")) {
          loadProperties(zis, configProperties);
        } else if (entry.getName().endsWith("application.yml")) {
          loadYaml(zis, configProperties);
        }
      }
    }
  }

  private void loadProperties(InputStream inputStream, Map<String, String> configProperties) throws IOException {
    Properties props = new Properties();
    props.load(inputStream);
    props.forEach((key, value) -> configProperties.put(key.toString(), value.toString()));
  }

  private void loadYaml(InputStream inputStream, Map<String, String> configProperties) {
    Yaml yaml = new Yaml();
    Map<String, Object> yamlMap = yaml.load(inputStream);
    flattenYamlMap(yamlMap, "", configProperties);
  }

  private void flattenYamlMap(Map<String, Object> yamlMap, String prefix, Map<String, String> result) {
    for (Map.Entry<String, Object> entry : yamlMap.entrySet()) {
      String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
      if (entry.getValue() instanceof Map) {
        @SuppressWarnings("unchecked")
        Map<String, Object> nestedMap = (Map<String, Object>) entry.getValue();
        flattenYamlMap(nestedMap, key, result);
      } else {
        result.put(key, entry.getValue().toString());
      }
    }
  }

  private void saveDiagrams(String projectName) {
    saveDiagramToFile(latestSequenceDiagram, "sequence_" + projectName);
    // saveDiagramToFile(latestClassDiagram, "class_" + projectName);
    // saveDiagramToFile(latestComponentDiagram, "component_" + projectName);
    // saveDiagramToFile(latestStateDiagram, "state_" + projectName);
    // saveDiagramToFile(latestActivityDiagram, "activity_" + projectName);
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
      // case "sequence" -> sequenceDiagramGenerator.combineDiagrams(diagrams);
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
    Path path = Paths.get(projectPath);

    if (Files.isDirectory(path)) {
      parseDirectory(path, allClasses);
    } else if (projectPath.endsWith(".jar")) {
      parseJarFile(projectPath, allClasses);
    } else if (projectPath.endsWith(".zip")) {
      Path tempDir = Files.createTempDirectory("unzipped_project");
      unzipFile(path, tempDir);
      parseDirectory(tempDir, allClasses);
      Files.walkFileTree(tempDir, new SimpleFileVisitor<Path>() {
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
          Files.delete(file);
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
          Files.delete(dir);
          return FileVisitResult.CONTINUE;
        }
      });
    } else {
      logger.error("Unsupported file type: {}", projectPath);
    }

    return allClasses;
  }

  private void parseDirectory(Path directory, List<ClassNode> allClasses) throws IOException {
    try (Stream<Path> walk = Files.walk(directory)) {
      walk.filter(Files::isRegularFile)
          .filter(p -> p.toString().endsWith(".class"))
          .forEach(classFile -> {
            try {
              byte[] classBytes = Files.readAllBytes(classFile);
              ClassNode classNode = parseClassFile(classBytes);
              if (classNode != null) {
                allClasses.add(classNode);
              }
            } catch (IOException e) {
              logger.error("Error reading class file: " + e.getMessage(), e);
            }
          });
    }
  }

  private void unzipFile(Path zipFile, Path destDir) throws IOException {
    try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile.toFile()))) {
      ZipEntry zipEntry = zis.getNextEntry();
      while (zipEntry != null) {
        Path newPath = destDir.resolve(zipEntry.getName());
        if (zipEntry.isDirectory()) {
          Files.createDirectories(newPath);
        } else {
          if (!Files.exists(newPath.getParent())) {
            Files.createDirectories(newPath.getParent());
          }
          Files.copy(zis, newPath);
        }
        zipEntry = zis.getNextEntry();
      }
      zis.closeEntry();
    }
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

  public List<APIInfo> getLatestExposedAPIInventory() {
    return latestExposedAPIInventory;
  }

  public List<DatabaseChangelogScanner.DatabaseChange> scanDatabaseChangeLogs(String projectPath) throws IOException {
    logger.info("Scanning database changelogs for project: {}", projectPath);
    List<DatabaseChangelogScanner.DatabaseChange> allChanges = new ArrayList<>();
    Path path = Paths.get(projectPath);

    if (Files.isDirectory(path)) {
      scanAllFiles(path, allChanges);
    } else if (projectPath.endsWith(".jar") || projectPath.endsWith(".war")) {
      scanArchiveFile(projectPath, allChanges);
    } else if (projectPath.endsWith(".zip")) {
      Path tempDir = Files.createTempDirectory("unzipped_project");
      unzipFile(path, tempDir);
      scanAllFiles(tempDir, allChanges);
      deleteDirectory(tempDir);
    } else {
      logger.error("Unsupported file type for database changelog scanning: {}", projectPath);
    }

    logger.info("Total database changes found: {}", allChanges.size());
    this.latestDatabaseChanges = allChanges;
    return allChanges;
  }

  private void scanAllFiles(Path directory, List<DatabaseChangelogScanner.DatabaseChange> allChanges)
      throws IOException {
    Path masterChangelogPath = findMasterChangelog(directory);
    if (masterChangelogPath != null) {
      scanMasterChangelog(masterChangelogPath, allChanges);
    } else {
      // Fallback to scanning all files if no master changelog is found
      scanAllChangelogFiles(directory, allChanges);
    }
  }

  private Path findMasterChangelog(Path directory) throws IOException {
    try (Stream<Path> walk = Files.walk(directory)) {
      return walk
          .filter(p -> !Files.isDirectory(p))
          .filter(p -> p.getFileName().toString().toLowerCase().contains("master") &&
              p.toString().endsWith(".xml"))
          .findFirst()
          .orElse(null);
    }
  }

  private void scanMasterChangelog(Path masterChangelogPath, List<DatabaseChangelogScanner.DatabaseChange> allChanges)
      throws IOException {
    List<String> includedFiles = databaseChangelogScanner.extractIncludedFiles(masterChangelogPath);
    for (String includedFile : includedFiles) {
      Path includedPath = masterChangelogPath.getParent().resolve(includedFile);
      if (Files.exists(includedPath)) {
        List<DatabaseChangelogScanner.DatabaseChange> changes = databaseChangelogScanner
            .scanChangelog(includedPath.toString());
        allChanges.addAll(changes);
      } else {
        logger.warn("Included changelog file not found: {}", includedPath);
      }
    }
  }

  private void scanAllChangelogFiles(Path directory, List<DatabaseChangelogScanner.DatabaseChange> allChanges)
      throws IOException {
    try (Stream<Path> walk = Files.walk(directory)) {
      List<Path> changeLogFiles = walk
          .filter(p -> !Files.isDirectory(p))
          .filter(p -> {
            String fileName = p.getFileName().toString().toLowerCase();
            return (fileName.endsWith(".xml") || fileName.endsWith(".sql") ||
                fileName.endsWith(".yaml") || fileName.endsWith(".yml") ||
                fileName.endsWith(".json"));
          })
          .collect(Collectors.toList());

      logger.info("Found {} potential changelog files", changeLogFiles.size());

      for (Path changeLogFile : changeLogFiles) {
        logger.debug("Scanning changelog file: {}", changeLogFile);
        List<DatabaseChangelogScanner.DatabaseChange> changes = databaseChangelogScanner
            .scanChangelog(changeLogFile.toString());
        allChanges.addAll(changes);
      }
    }
  }

  private void scanArchiveFile(String archivePath, List<DatabaseChangelogScanner.DatabaseChange> allChanges)
      throws IOException {
    try (JarFile jarFile = new JarFile(archivePath)) {
      Enumeration<JarEntry> entries = jarFile.entries();
      while (entries.hasMoreElements()) {
        JarEntry entry = entries.nextElement();
        String entryName = entry.getName().toLowerCase();
        if (!entry.isDirectory() &&
            (entryName.contains("changelog") || entryName.contains("liquibase")) &&
            (entryName.endsWith(".xml") || entryName.endsWith(".sql") ||
                entryName.endsWith(".yaml") || entryName.endsWith(".yml") ||
                entryName.endsWith(".json"))) {
          try (InputStream is = jarFile.getInputStream(entry)) {
            List<DatabaseChangelogScanner.DatabaseChange> changes = databaseChangelogScanner
                .scanChangelog(is, entry.getName());
            allChanges.addAll(changes);
          }
        }
      }
    }
  }

  private void deleteDirectory(Path directory) throws IOException {
    Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        Files.delete(file);
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
        Files.delete(dir);
        return FileVisitResult.CONTINUE;
      }
    });
  }

  public List<DatabaseChangelogScanner.DatabaseChange> getLatestDatabaseChanges() {
    return latestDatabaseChanges;
  }
}