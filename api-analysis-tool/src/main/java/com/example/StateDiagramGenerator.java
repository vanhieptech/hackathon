package com.example;

import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.*;

public class StateDiagramGenerator {

  public String generateStateDiagram(List<ClassNode> allClasses) {
    StringBuilder sb = new StringBuilder();
    sb.append("@startuml\n");

    for (ClassNode classNode : allClasses) {
      if (isStateMachine(classNode)) {
        generateStatesForClass(sb, classNode);
      }
    }

    sb.append("@enduml");
    return sb.toString();
  }

  private boolean isStateMachine(ClassNode classNode) {
    return classNode.name.endsWith("StateMachine") || classNode.interfaces.contains("java/util/concurrent/locks/Lock");
  }

  private void generateStatesForClass(StringBuilder sb, ClassNode classNode) {
    sb.append("state ").append(classNode.name.replace('/', '.')).append(" {\n");

    Set<String> states = new HashSet<>();
    Map<String, Set<String>> transitions = new HashMap<>();

    for (MethodNode methodNode : classNode.methods) {
      if (isStateTransition(methodNode)) {
        String fromState = extractFromState(methodNode);
        String toState = extractToState(methodNode);
        states.add(fromState);
        states.add(toState);
        transitions.computeIfAbsent(fromState, k -> new HashSet<>()).add(toState);
      }
    }

    for (String state : states) {
      sb.append("  state ").append(state).append("\n");
    }

    for (Map.Entry<String, Set<String>> entry : transitions.entrySet()) {
      String fromState = entry.getKey();
      for (String toState : entry.getValue()) {
        sb.append("  ").append(fromState).append(" --> ").append(toState).append("\n");
      }
    }

    sb.append("}\n");
  }

  private boolean isStateTransition(MethodNode methodNode) {
    return methodNode.name.startsWith("transition") || methodNode.name.startsWith("set")
        || methodNode.name.startsWith("change");
  }

  private String extractFromState(MethodNode methodNode) {
    String[] parts = methodNode.name.split("From|To");
    return parts.length > 1 ? parts[1] : "Unknown";
  }

  private String extractToState(MethodNode methodNode) {
    String[] parts = methodNode.name.split("To");
    return parts.length > 1 ? parts[1] : "Unknown";
  }

  public String combineDiagrams(List<String> diagrams) {
    StringBuilder combined = new StringBuilder("@startuml\n");

    Set<String> allStates = new LinkedHashSet<>();
    Set<String> allTransitions = new LinkedHashSet<>();

    for (String diagram : diagrams) {
      String[] lines = diagram.split("\n");
      for (String line : lines) {
        if (line.startsWith("state ")) {
          allStates.add(line);
        } else if (line.contains("-->")) {
          allTransitions.add(line);
        }
      }
    }

    for (String state : allStates) {
      combined.append(state).append("\n");
    }

    combined.append("\n");

    for (String transition : allTransitions) {
      combined.append(transition).append("\n");
    }

    combined.append("@enduml");
    return combined.toString();
  }
}