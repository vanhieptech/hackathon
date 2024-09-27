package com.example;

import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class ClassDiagramGenerator {

  public String generateClassDiagram(List<ClassNode> allClasses) {
    StringBuilder sb = new StringBuilder();
    sb.append("@startuml\n");

    for (ClassNode classNode : allClasses) {
      generateClassDefinition(sb, classNode);
    }

    for (ClassNode classNode : allClasses) {
      generateRelationships(sb, classNode);
    }

    sb.append("@enduml");
    return sb.toString();
  }

  private void generateClassDefinition(StringBuilder sb, ClassNode classNode) {
    sb.append("class ").append(classNode.name.replace('/', '.')).append(" {\n");

    for (FieldNode field : classNode.fields) {
      sb.append("  ").append(field.desc).append(" ").append(field.name).append("\n");
    }

    for (MethodNode method : classNode.methods) {
      if (!method.name.equals("<init>") && !method.name.equals("<clinit>")) {
        sb.append("  ").append(method.desc).append(" ").append(method.name).append("()\n");
      }
    }

    sb.append("}\n\n");
  }

  private void generateRelationships(StringBuilder sb, ClassNode classNode) {
    if (classNode.superName != null && !classNode.superName.equals("java/lang/Object")) {
      sb.append(classNode.superName.replace('/', '.')).append(" <|-- ")
          .append(classNode.name.replace('/', '.')).append("\n");
    }

    for (String interfaceName : classNode.interfaces) {
      sb.append(interfaceName.replace('/', '.')).append(" <|.. ")
          .append(classNode.name.replace('/', '.')).append("\n");
    }
  }

  public String combineDiagrams(List<String> diagrams) {
    StringBuilder combined = new StringBuilder("@startuml\n");

    Set<String> allClasses = new LinkedHashSet<>();
    Set<String> allRelationships = new LinkedHashSet<>();

    for (String diagram : diagrams) {
      String[] lines = diagram.split("\n");
      for (String line : lines) {
        if (line.startsWith("class ") || line.startsWith("interface ") || line.startsWith("enum ")) {
          allClasses.add(line);
        } else if (line.contains("-->") || line.contains("<--") || line.contains("-") || line.contains("+")) {
          allRelationships.add(line);
        }
      }
    }

    for (String classDeclaration : allClasses) {
      combined.append(classDeclaration).append("\n");
    }

    combined.append("\n");

    for (String relationship : allRelationships) {
      combined.append(relationship).append("\n");
    }

    combined.append("@enduml");
    return combined.toString();
  }
}