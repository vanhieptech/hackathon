package com.example;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

@Component
public class ProjectScanner {
  private static final Logger logger = LoggerFactory.getLogger(ProjectScanner.class);
  private final Map<String, List<ClassNode>> projectCache = new ConcurrentHashMap<>();

  public Map<String, String> loadProjectConfigProperties(Path projectPath) throws IOException {
    Map<String, String> properties = new HashMap<>();
    try (JarFile jarFile = new JarFile(projectPath.toFile())) {
      JarEntry entry = jarFile.getJarEntry("application.properties");
      if (entry != null) {
        try (InputStream is = jarFile.getInputStream(entry)) {
          Properties props = new Properties();
          props.load(is);
          props.forEach((key, value) -> properties.put(key.toString(), value.toString()));
        }
      }
    }
    return properties;
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