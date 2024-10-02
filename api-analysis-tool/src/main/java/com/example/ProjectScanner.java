package com.example;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
public class ProjectScanner {
  private static final Logger logger = LoggerFactory.getLogger(ProjectScanner.class);
  private final Map<String, List<ClassNode>> projectCache = new ConcurrentHashMap<>();

  public Map<String, String> loadProjectConfigProperties(String projectPath) throws IOException {
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

  public List<ClassNode> parseJavaClasses(Path projectPath) throws IOException {
    String projectName = projectPath.getFileName().toString();
    if (projectCache.containsKey(projectName)) {
      logger.info("Using cached classes for project: {}", projectName);
      return projectCache.get(projectName);
    }

    List<ClassNode> classes = new ArrayList<>();
    try (JarFile jarFile = new JarFile(projectPath.toFile())) {
      Enumeration<JarEntry> entries = jarFile.entries();
      while (entries.hasMoreElements()) {
        JarEntry entry = entries.nextElement();
        if (entry.getName().endsWith(".class")) {
          try (InputStream is = jarFile.getInputStream(entry)) {
            ClassReader reader = new ClassReader(is);
            ClassNode classNode = new ClassNode();
            reader.accept(classNode, 0);
            classes.add(classNode);
          }
        }
      }
    }
    projectCache.put(projectName, classes);
    return classes;
  }
}