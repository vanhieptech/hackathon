package com.example;

import org.objectweb.asm.tree.ClassNode;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.LinkedHashSet;

public class ComponentDiagramGenerator {

  public String generateComponentDiagram(List<ClassNode> allClasses) {
    StringBuilder sb = new StringBuilder();
    sb.append("@startuml\n");

    Map<String, Set<String>> packageDependencies = analyzePackageDependencies(allClasses);

    for (String packageName : packageDependencies.keySet()) {
      sb.append("package \"").append(packageName).append("\" {\n");
      sb.append("  [").append(getLastPackagePart(packageName)).append("]\n");
      sb.append("}\n");
    }

    for (Map.Entry<String, Set<String>> entry : packageDependencies.entrySet()) {
      String sourcePackage = entry.getKey();
      for (String targetPackage : entry.getValue()) {
        sb.append("[").append(getLastPackagePart(sourcePackage)).append("] --> [")
            .append(getLastPackagePart(targetPackage)).append("]\n");
      }
    }

    sb.append("@enduml");
    return sb.toString();
  }

  private Map<String, Set<String>> analyzePackageDependencies(List<ClassNode> allClasses) {
    Map<String, Set<String>> packageDependencies = new HashMap<>();

    for (ClassNode classNode : allClasses) {
      String sourcePackage = getPackageName(classNode.name);
      packageDependencies.putIfAbsent(sourcePackage, new HashSet<>());

      // Analyze superclass
      if (classNode.superName != null) {
        String targetPackage = getPackageName(classNode.superName);
        if (!sourcePackage.equals(targetPackage)) {
          packageDependencies.get(sourcePackage).add(targetPackage);
        }
      }

      // Analyze interfaces
      for (String interfaceName : classNode.interfaces) {
        String targetPackage = getPackageName(interfaceName);
        if (!sourcePackage.equals(targetPackage)) {
          packageDependencies.get(sourcePackage).add(targetPackage);
        }
      }
    }

    return packageDependencies;
  }

  private String getPackageName(String className) {
    int lastSlashIndex = className.lastIndexOf('/');
    return lastSlashIndex == -1 ? "" : className.substring(0, lastSlashIndex).replace('/', '.');
  }

  private String getLastPackagePart(String packageName) {
    int lastDotIndex = packageName.lastIndexOf('.');
    return lastDotIndex == -1 ? packageName : packageName.substring(lastDotIndex + 1);
  }

  public String combineDiagrams(List<String> diagrams) {
    StringBuilder combined = new StringBuilder("@startuml\n");

    Set<String> allComponents = new LinkedHashSet<>();
    Set<String> allInterfaces = new LinkedHashSet<>();
    Set<String> allRelationships = new LinkedHashSet<>();

    for (String diagram : diagrams) {
      String[] lines = diagram.split("\n");
      for (String line : lines) {
        if (line.startsWith("component ")) {
          allComponents.add(line);
        } else if (line.startsWith("interface ")) {
          allInterfaces.add(line);
        } else if (line.contains("-->") || line.contains("<--")) {
          allRelationships.add(line);
        }
      }
    }

    for (String component : allComponents) {
      combined.append(component).append("\n");
    }

    combined.append("\n");

    for (String interfaceDeclaration : allInterfaces) {
      combined.append(interfaceDeclaration).append("\n");
    }

    combined.append("\n");

    for (String relationship : allRelationships) {
      combined.append(relationship).append("\n");
    }

    combined.append("@enduml");
    return combined.toString();
  }
}