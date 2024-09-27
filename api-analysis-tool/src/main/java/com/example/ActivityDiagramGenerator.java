package com.example;

import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;

import java.util.*;

public class ActivityDiagramGenerator {

  public String generateActivityDiagram(List<ClassNode> allClasses) {
    StringBuilder sb = new StringBuilder();
    sb.append("@startuml\n");

    for (ClassNode classNode : allClasses) {
      for (MethodNode methodNode : classNode.methods) {
        if (isSignificantMethod(methodNode)) {
          generateActivityForMethod(sb, classNode, methodNode);
        }
      }
    }

    sb.append("@enduml");
    return sb.toString();
  }

  private boolean isSignificantMethod(MethodNode methodNode) {
    return !methodNode.name.equals("<init>") && !methodNode.name.equals("<clinit>");
  }

  private void generateActivityForMethod(StringBuilder sb, ClassNode classNode, MethodNode methodNode) {
    sb.append("partition ").append(classNode.name.replace('/', '.')).append(".").append(methodNode.name).append(" {\n");

    Map<LabelNode, String> labelMap = new HashMap<>();
    int activityCounter = 0;

    for (AbstractInsnNode insn : methodNode.instructions) {
      if (insn instanceof LabelNode) {
        LabelNode labelNode = (LabelNode) insn;
        String activityName = "Activity_" + (++activityCounter);
        labelMap.put(labelNode, activityName);
        sb.append("  :").append(activityName).append(";\n");
      } else if (insn instanceof JumpInsnNode) {
        JumpInsnNode jumpInsn = (JumpInsnNode) insn;
        String fromActivity = labelMap.get(jumpInsn.getPrevious());
        String toActivity = labelMap.get(jumpInsn.label);
        if (fromActivity != null && toActivity != null) {
          sb.append("  ").append(fromActivity).append(" --> ").append(toActivity).append(";\n");
        }
      }
    }

    sb.append("}\n");
  }

  public String combineDiagrams(List<String> diagrams) {
    StringBuilder combined = new StringBuilder("@startuml\n");

    Set<String> allActivities = new LinkedHashSet<>();
    Set<String> allTransitions = new LinkedHashSet<>();
    Set<String> allDecisions = new LinkedHashSet<>();

    for (String diagram : diagrams) {
      String[] lines = diagram.split("\n");
      for (String line : lines) {
        if (line.startsWith(":")) {
          allActivities.add(line);
        } else if (line.contains("-->")) {
          allTransitions.add(line);
        } else if (line.contains("if ") || line.contains("else") || line.contains("endif")) {
          allDecisions.add(line);
        }
      }
    }

    combined.append("start\n");

    for (String activity : allActivities) {
      combined.append(activity).append("\n");
    }

    for (String decision : allDecisions) {
      combined.append(decision).append("\n");
    }

    for (String transition : allTransitions) {
      combined.append(transition).append("\n");
    }

    combined.append("stop\n");
    combined.append("@enduml");
    return combined.toString();
  }
}