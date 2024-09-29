package com.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;

public class DatabaseChangelogScanner {
  private static final Logger logger = LoggerFactory.getLogger(DatabaseChangelogScanner.class);

  public List<DatabaseChange> scanChangelog(String changelogPath) throws IOException {
    try (InputStream inputStream = Files.newInputStream(Paths.get(changelogPath))) {
      return scanChangelog(inputStream, changelogPath);
    }
  }

  public List<DatabaseChange> scanChangelog(InputStream inputStream, String fileName) throws IOException {
    String lowerFileName = fileName.toLowerCase();
    if (lowerFileName.endsWith(".xml")) {
      return scanXmlChangelog(inputStream, fileName);
    } else if (lowerFileName.endsWith(".sql")) {
      return scanSqlChangelog(inputStream);
    } else if (lowerFileName.endsWith(".yaml") || lowerFileName.endsWith(".yml")) {
      return scanYamlChangelog(inputStream);
    } else if (lowerFileName.endsWith(".json")) {
      return scanJsonChangelog(inputStream);
    } else {
      logger.warn("Unsupported changelog file type: {}", fileName);
      return new ArrayList<>();
    }
  }

  private List<DatabaseChange> scanXmlChangelog(InputStream inputStream, String changelogPath) throws IOException {
    List<DatabaseChange> changes = new ArrayList<>();
    try {
      DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
      DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
      Document doc = dBuilder.parse(inputStream);
      doc.getDocumentElement().normalize();

      NodeList changeSetList = doc.getElementsByTagName("changeSet");
      for (int i = 0; i < changeSetList.getLength(); i++) {
        Element changeSet = (Element) changeSetList.item(i);
        String id = changeSet.getAttribute("id");
        String author = changeSet.getAttribute("author");

        NodeList changeTypes = changeSet.getChildNodes();
        for (int j = 0; j < changeTypes.getLength(); j++) {
          if (changeTypes.item(j).getNodeType() == Node.ELEMENT_NODE) {
            Element changeType = (Element) changeTypes.item(j);
            String type = changeType.getTagName();
            String sql = extractSqlFromChangeType(changeType);
            changes.add(new DatabaseChange(id, author, type, sql, changelogPath));
          }
        }
      }
    } catch (Exception e) {
      logger.error("Error scanning XML changelog file: {}", changelogPath, e);
    }
    return changes;
  }

  private List<DatabaseChange> scanYamlChangelog(InputStream inputStream) throws IOException {
    // Implement YAML parsing logic here
    // You may need to add a YAML parsing library to your project
    logger.warn("YAML changelog scanning not yet implemented");
    return new ArrayList<>();
  }

  private List<DatabaseChange> scanJsonChangelog(InputStream inputStream) throws IOException {
    // Implement JSON parsing logic here
    // You may need to add a JSON parsing library to your project
    logger.warn("JSON changelog scanning not yet implemented");
    return new ArrayList<>();
  }

  public List<String> extractIncludedFiles(Path masterChangelogPath) throws IOException {
    List<String> includedFiles = new ArrayList<>();
    try (InputStream inputStream = Files.newInputStream(masterChangelogPath)) {
      DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
      DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
      Document doc = dBuilder.parse(inputStream);
      doc.getDocumentElement().normalize();

      NodeList includeNodes = doc.getElementsByTagName("include");
      for (int i = 0; i < includeNodes.getLength(); i++) {
        Element includeElement = (Element) includeNodes.item(i);
        String file = includeElement.getAttribute("file");
        if (!file.isEmpty()) {
          includedFiles.add(file);
        }
      }
    } catch (Exception e) {
      logger.error("Error extracting included files from master changelog", e);
    }
    return includedFiles;
  }

  private String extractSqlFromChangeType(Element changeType) {
    switch (changeType.getTagName()) {
      case "sql":
        return changeType.getTextContent().trim();
      case "createTable":
        return generateCreateTableSql(changeType);
      case "addColumn":
        return generateAddColumnSql(changeType);
      case "dropTable":
        return "DROP TABLE " + changeType.getAttribute("tableName") + ";";
      case "dropColumn":
        return "ALTER TABLE " + changeType.getAttribute("tableName") +
            " DROP COLUMN " + changeType.getAttribute("columnName") + ";";
      default:
        return "-- Unsupported change type: " + changeType.getTagName();
    }
  }

  private String generateCreateTableSql(Element createTable) {
    StringBuilder sql = new StringBuilder("CREATE TABLE " + createTable.getAttribute("tableName") + " (\n");
    NodeList columns = createTable.getElementsByTagName("column");
    for (int i = 0; i < columns.getLength(); i++) {
      Element column = (Element) columns.item(i);
      sql.append("  ").append(column.getAttribute("name")).append(" ")
          .append(column.getAttribute("type"));
      if (column.hasAttribute("defaultValue")) {
        sql.append(" DEFAULT ").append(column.getAttribute("defaultValue"));
      }
      if ("true".equals(column.getAttribute("autoIncrement"))) {
        sql.append(" AUTO_INCREMENT");
      }
      if (i < columns.getLength() - 1) {
        sql.append(",");
      }
      sql.append("\n");
    }
    sql.append(");");
    return sql.toString();
  }

  private String generateAddColumnSql(Element addColumn) {
    return "ALTER TABLE " + addColumn.getAttribute("tableName") +
        " ADD COLUMN " + addColumn.getAttribute("name") +
        " " + addColumn.getAttribute("type") + ";";
  }

  private List<DatabaseChange> scanSqlChangelog(InputStream inputStream) throws IOException {
    List<DatabaseChange> changes = new ArrayList<>();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
      StringBuilder currentSql = new StringBuilder();
      String currentId = null;
      String currentAuthor = null;
      String line;

      while ((line = reader.readLine()) != null) {
        line = line.trim();
        if (line.startsWith("--changeset")) {
          addChangeIfExists(changes, currentId, currentAuthor, currentSql);
          String[] parts = line.split(":", 3);
          if (parts.length >= 3) {
            currentId = parts[1].trim();
            currentAuthor = parts[2].trim();
          } else {
            logger.warn("Invalid changeset format: {}", line);
          }
        } else if (!line.startsWith("--") && !line.isEmpty()) {
          currentSql.append(line).append("\n");
        }
      }

      addChangeIfExists(changes, currentId, currentAuthor, currentSql);
    }
    return changes;
  }

  private void addChangeIfExists(List<DatabaseChange> changes, String id, String author, StringBuilder sql) {
    if (sql.length() > 0) {
      changes.add(new DatabaseChange(id, author, "sql", sql.toString().trim(), null));
      sql.setLength(0);
    }
  }

  public static class DatabaseChange {
    private String id;
    private String author;
    private String type;
    private String sql;
    private String sourceFile;

    public DatabaseChange(String id, String author, String type, String sql, String sourceFile) {
      this.id = id;
      this.author = author;
      this.type = type;
      this.sql = sql;
      this.sourceFile = sourceFile;
    }

    // Add getter for sourceFile
    public String getSourceFile() {
      return sourceFile;
    }

    // Getters
    public String getId() {
      return id;
    }

    public String getAuthor() {
      return author;
    }

    public String getType() {
      return type;
    }

    public String getSql() {
      return sql;
    }
  }
}