package com.example;

import liquibase.changelog.ChangeLogParameters;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.DatabaseChangeLog;
import liquibase.exception.LiquibaseException;
import liquibase.parser.ChangeLogParser;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.ResourceAccessor;
import liquibase.structure.core.Column;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.resource.CompositeResourceAccessor;
import liquibase.resource.FileSystemResourceAccessor;
import liquibase.change.AddColumnConfig;
import liquibase.change.Change;
import liquibase.change.ColumnConfig;
import liquibase.change.core.AddColumnChange;
import liquibase.change.core.AddForeignKeyConstraintChange;
import liquibase.change.core.CreateTableChange;
import liquibase.change.core.DropTableChange;
import liquibase.change.core.RawSQLChange;
import liquibase.change.core.SQLFileChange;
import liquibase.resource.Resource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

@Component
public class LiquibaseChangeScanner {
  private static final Logger logger = LoggerFactory.getLogger(LiquibaseChangeScanner.class);
  private Map<String, String> configProperties;
  private final Set<String> processedChangeLogs = new HashSet<>();

  public List<ChangeSetInfo> scanJarForDatabaseChanges(String jarPath, Map<String, String> configProperties)
      throws Exception {
    logger.info("Starting Liquibase change scan for JAR: {}", jarPath);
    this.configProperties = configProperties;
    String changeLogPath = getChangeLogPath();
    return parseChangeLogFromJar(jarPath, changeLogPath);
  }

  private List<ChangeSetInfo> parseChangeLogFromJar(String jarPath, String changeLogPath) throws Exception {
    logger.info("Parsing Liquibase changelog from JAR: {}", changeLogPath);

    ResourceAccessor resourceAccessor = createResourceAccessorForJar(jarPath);

    // Remove the "classpath:" prefix if present
    String effectiveChangeLogPath = changeLogPath.replaceFirst("^classpath:", "");

    // Log the contents of the JAR for debugging
    logJarContents(jarPath);

    var path = "BOOT-INF/classes/" + effectiveChangeLogPath;
    List<Resource> resources = resourceAccessor.getAll(path);
    if (!resources.isEmpty()) {
      logger.info("Found changelog at path: {}", path);
      return parseChangeLog(path, resourceAccessor);
    }

    throw new FileNotFoundException("Changelog file not found: " + effectiveChangeLogPath);
  }

  private void logJarContents(String jarPath) {
    try (JarFile jarFile = new JarFile(jarPath)) {
      logger.info("Contents of JAR file {}:", jarPath);
      Enumeration<JarEntry> entries = jarFile.entries();
      while (entries.hasMoreElements()) {
        JarEntry entry = entries.nextElement();
        logger.info("  {}", entry.getName());
      }
    } catch (IOException e) {
      logger.error("Error reading JAR file: {}", e.getMessage());
    }
  }

  private List<ChangeSetInfo> parseChangeLog(String changeLogPath, ResourceAccessor resourceAccessor) throws Exception {
    ChangeLogParser parser = ChangeLogParserFactory.getInstance().getParser(changeLogPath, resourceAccessor);
    DatabaseChangeLog changeLog = parser.parse(changeLogPath, new ChangeLogParameters(), resourceAccessor);

    List<ChangeSetInfo> allChangeSets = new ArrayList<>();
    collectChangeSets(changeLog, allChangeSets, resourceAccessor);

    logger.info("Found {} change sets", allChangeSets.size());
    return allChangeSets;
  }

  private ResourceAccessor createResourceAccessorForJar(String jarPath) throws IOException {
    List<ResourceAccessor> accessors = new ArrayList<>();

    // Create a temporary directory to extract JAR contents
    Path tempDir = Files.createTempDirectory("liquibase-temp");

    try (JarFile jarFile = new JarFile(jarPath)) {
      Enumeration<JarEntry> entries = jarFile.entries();
      while (entries.hasMoreElements()) {
        JarEntry entry = entries.nextElement();
        if (!entry.isDirectory()) {
          Path targetPath = tempDir.resolve(entry.getName());
          Files.createDirectories(targetPath.getParent());
          try (InputStream is = jarFile.getInputStream(entry)) {
            Files.copy(is, targetPath, StandardCopyOption.REPLACE_EXISTING);
          }
        }
      }
    }

    // Add FileSystemResourceAccessor for the extracted contents
    accessors.add(new FileSystemResourceAccessor(tempDir.toString()));

    // Add ClassLoaderResourceAccessor for resources in the classpath (including
    // JARs)
    accessors.add(new ClassLoaderResourceAccessor(getClass().getClassLoader()));

    return new CompositeResourceAccessor(accessors.toArray(new ResourceAccessor[0]));
  }

  private String getChangeLogPath() {
    String changeLogPath = configProperties.get("spring.liquibase.change-log");
    if (changeLogPath == null) {
      logger.warn(
          "Liquibase changelog path not found in configuration. Using default: db/changelog/db.changelog-master.xml");
      changeLogPath = "db/changelog/db.changelog-master.xml";
    }
    return changeLogPath;
  }

  private void collectChangeSets(DatabaseChangeLog changeLog, List<ChangeSetInfo> allChangeSets,
      ResourceAccessor resourceAccessor) throws Exception {
    if (processedChangeLogs.contains(changeLog.getFilePath())) {
      return; // Avoid processing the same changelog multiple times
    }
    processedChangeLogs.add(changeLog.getFilePath());

    for (ChangeSet changeSet : changeLog.getChangeSets()) {
      ChangeSetInfo changeSetInfo = new ChangeSetInfo(
          changeSet.getId(),
          changeSet.getAuthor(),
          changeSet.getFilePath(),
          changeSet.getComments(),
          generateSql(changeSet, resourceAccessor),
          null);
      allChangeSets.add(changeSetInfo);

      if (changeSet.getFilePath() != null && !changeSet.getFilePath().equals(changeLog.getFilePath())) {
        logger.debug("Processing included changelog: {}", changeSet.getFilePath());
        ChangeLogParser parser = ChangeLogParserFactory.getInstance().getParser(changeSet.getFilePath(),
            resourceAccessor);
        DatabaseChangeLog includedChangeLog = parser.parse(changeSet.getFilePath(), changeLog.getChangeLogParameters(),
            resourceAccessor);
        collectChangeSets(includedChangeLog, allChangeSets, resourceAccessor);
      }
    }
  }

  private String generateSql(ChangeSet changeSet, ResourceAccessor resourceAccessor)
      throws LiquibaseException, IOException {
    StringBuilder sql = new StringBuilder();

    for (Change change : changeSet.getChanges()) {
      if (change instanceof RawSQLChange) {
        sql.append(((RawSQLChange) change).getSql()).append(";\n");
      } else if (change instanceof SQLFileChange) {
        sql.append(handleSqlFileChange((SQLFileChange) change, resourceAccessor)).append(";\n");
      } else if (change instanceof CreateTableChange) {
        sql.append(handleCreateTableChange((CreateTableChange) change)).append(";\n");
      } else if (change instanceof AddColumnChange) {
        sql.append(handleAddColumnChange((AddColumnChange) change)).append(";\n");
      } else if (change instanceof DropTableChange) {
        sql.append(handleDropTableChange((DropTableChange) change)).append(";\n");
      } else if (change instanceof AddForeignKeyConstraintChange) {
        sql.append(handleAddForeignKeyConstraintChange((AddForeignKeyConstraintChange) change)).append(";\n");
      } else {
        sql.append("-- Unsupported change type: ").append(change.getClass().getSimpleName()).append("\n");
      }
    }

    return sql.toString();
  }

  private String handleSqlFileChange(SQLFileChange change, ResourceAccessor resourceAccessor) throws IOException {
    String sqlFilePath = change.getPath();
    String encoding = change.getEncoding() != null ? change.getEncoding() : StandardCharsets.UTF_8.name();

    List<Resource> resources = resourceAccessor.getAll(sqlFilePath);
    if (resources.isEmpty()) {
      throw new IOException("Could not find SQL file: " + sqlFilePath);
    }

    try (InputStream is = resources.get(0).openInputStream()) {
      return new String(is.readAllBytes(), encoding);
    }
  }

  private String handleCreateTableChange(CreateTableChange change) {
    StringBuilder sql = new StringBuilder("CREATE TABLE ");
    sql.append(change.getTableName()).append(" (\n");

    List<String> columnDefinitions = change.getColumns().stream()
        .map(this::formatColumnDefinition)
        .collect(Collectors.toList());

    sql.append(String.join(",\n", columnDefinitions));

    // Add primary key constraint if specified
    String primaryKeyColumns = change.getColumns().stream()
        .filter(
            column -> column.getConstraints() != null && Boolean.TRUE.equals(column.getConstraints().isPrimaryKey()))
        .map(ColumnConfig::getName)
        .collect(Collectors.joining(", "));

    if (!primaryKeyColumns.isEmpty()) {
      sql.append(",\n").append("PRIMARY KEY (").append(primaryKeyColumns).append(")");
    }

    sql.append("\n)");
    return sql.toString();
  }

  private String formatColumnDefinition(ColumnConfig column) {
    StringBuilder columnSql = new StringBuilder(column.getName())
        .append(" ").append(column.getType());

    if (column.isAutoIncrement() != null && column.isAutoIncrement()) {
      columnSql.append(" AUTO_INCREMENT");
    }
    if (column.getDefaultValue() != null) {
      columnSql.append(" DEFAULT ").append(column.getDefaultValue());
    }
    if (column.getConstraints() != null) {
      if (column.getConstraints().isNullable() != null && !column.getConstraints().isNullable()) {
        columnSql.append(" NOT NULL");
      }
      if (Boolean.TRUE.equals(column.getConstraints().isUnique())) {
        columnSql.append(" UNIQUE");
      }
    }
    if (column.getRemarks() != null) {
      columnSql.append(" COMMENT '").append(column.getRemarks()).append("'");
    }

    return columnSql.toString();
  }

  private String handleAddColumnChange(AddColumnChange change) {
    return "ALTER TABLE " + change.getTableName() + " ADD COLUMN " +
        formatColumnDefinition(change.getColumns().get(0));
  }

  private String handleDropTableChange(DropTableChange change) {
    return "DROP TABLE " + change.getTableName();
  }

  private String handleAddForeignKeyConstraintChange(AddForeignKeyConstraintChange change) {
    return "ALTER TABLE " + change.getBaseTableName() +
        " ADD CONSTRAINT " + change.getConstraintName() +
        " FOREIGN KEY (" + String.join(", ", change.getBaseColumnNames()) + ")" +
        " REFERENCES " + change.getReferencedTableName() +
        "(" + String.join(", ", change.getReferencedColumnNames()) + ")";
  }

  public void printChangeSetSummary(List<ChangeSetInfo> changeSets) {
    logger.info("Change Set Summary:");
    changeSets
        .sort(Comparator.comparing(ChangeSetInfo::getExecutionDate, Comparator.nullsLast(Comparator.naturalOrder())));
    for (ChangeSetInfo changeSet : changeSets) {
      logger.info("ID: {}, Author: {}, File: {}, Executed: {}",
          changeSet.getId(),
          changeSet.getAuthor(),
          changeSet.getFilePath(),
          changeSet.getExecutionDate());
      logger.info("Comments: {}", changeSet.getComments());
      logger.info("SQL:\n{}", changeSet.getSql());
      logger.info("--------------------");
    }
  }

  public static class ChangeSetInfo {
    private final String id;
    private final String author;
    private final String filePath;
    private final String comments;
    private final String sql;
    private final Date executionDate;

    public ChangeSetInfo(String id, String author, String filePath, String comments, String sql, Date executionDate) {
      this.id = id;
      this.author = author;
      this.filePath = filePath;
      this.comments = comments;
      this.sql = sql;
      this.executionDate = executionDate;
    }

    // Getters for all fields

    public String getId() {
      return id;
    }

    public String getAuthor() {
      return author;
    }

    public String getFilePath() {
      return filePath;
    }

    public String getComments() {
      return comments;
    }

    public String getSql() {
      return sql;
    }

    public Date getExecutionDate() {
      return executionDate;
    }
  }
}