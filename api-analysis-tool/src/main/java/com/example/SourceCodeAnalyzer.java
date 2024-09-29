package com.example;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class SourceCodeAnalyzer {

  public Map<String, ClassInfo> analyzeSourceCode(String projectPath) {
    Map<String, ClassInfo> classInfoMap = new HashMap<>();
    File projectDir = new File(projectPath);
    analyzeDirectory(projectDir, classInfoMap);
    return classInfoMap;
  }

  private void analyzeDirectory(File directory, Map<String, ClassInfo> classInfoMap) {
    File[] files = directory.listFiles();
    if (files != null) {
      for (File file : files) {
        if (file.isDirectory()) {
          analyzeDirectory(file, classInfoMap);
        } else if (file.getName().endsWith(".java")) {
          analyzeJavaFile(file, classInfoMap);
        }
      }
    }
  }

  private void analyzeJavaFile(File javaFile, Map<String, ClassInfo> classInfoMap) {
    try {
      CompilationUnit cu = StaticJavaParser.parse(javaFile);
      cu.findAll(ClassOrInterfaceDeclaration.class).forEach(classDecl -> {
        String className = classDecl.getNameAsString();
        String packageName = cu.getPackageDeclaration()
            .map(pd -> pd.getNameAsString())
            .orElse("");
        ClassInfo classInfo = new ClassInfo(className, packageName);

        classDecl.getMethods().forEach(method -> {
          String methodName = method.getNameAsString();
          List<String> parameters = method.getParameters().stream()
              .map(p -> p.getTypeAsString() + " " + p.getNameAsString())
              .collect(Collectors.toList());
          classInfo.addMethod(new ClassInfo.MethodInfo(methodName, parameters));

          method.findAll(MethodCallExpr.class).forEach(methodCall -> {
            classInfo.addMethodCall(methodName, methodCall.getNameAsString());
          });
        });

        classInfoMap.put(className, classInfo);
      });
    } catch (FileNotFoundException e) {
      e.printStackTrace();
    }
  }

  public Map<String, Set<String>> extractClassImports(Map<String, ClassInfo> sourceCodeInfo) {
    Map<String, Set<String>> classImports = new HashMap<>();

    for (Map.Entry<String, ClassInfo> entry : sourceCodeInfo.entrySet()) {
      String className = entry.getKey();
      ClassInfo classInfo = entry.getValue();
      Set<String> imports = extractImportsFromSource(classInfo.getSourceCode());
      classImports.put(className, imports);
    }

    return classImports;
  }

  private Set<String> extractImportsFromSource(String sourceCode) {
    Set<String> imports = new HashSet<>();
    Pattern importPattern = Pattern.compile("import\\s+([\\w.]+)\\s*;");
    Matcher matcher = importPattern.matcher(sourceCode);

    while (matcher.find()) {
      String importStatement = matcher.group(1);
      imports.add(importStatement);
    }

    return imports;
  }
}

class ClassInfo {
  String name;
  String packageName;
  List<MethodInfo> methods;
  Map<String, List<String>> methodCalls;
  Set<String> imports;
  Set<String> annotations;
  private String sourceCode;
  // Constructor, getters, and setters

  public ClassInfo(String name, String packageName, List<MethodInfo> methods, Map<String, List<String>> methodCalls,
      Set<String> imports) {
    this.name = name;
    this.packageName = packageName;
    this.methods = methods;
    this.methodCalls = methodCalls;
  }

  public ClassInfo(String name, String sourceCode, Set<String> imports, List<MethodInfo> methods) {
    this.name = name;
    this.sourceCode = sourceCode;
    this.imports = imports;
    this.methods = methods;
  }

  public String getSourceCode() {
    return sourceCode;
  }

  public void setSourceCode(String sourceCode) {
    this.sourceCode = sourceCode;
  }

  public void setAnnotations(Set<String> annotations) {
    this.annotations = annotations;
  }

  public void setImports(Set<String> imports) {
    this.imports = imports;
  }

  public ClassInfo(String name, String packageName) {
    this.name = name;
    this.packageName = packageName;
    this.methods = new ArrayList<>();
    this.methodCalls = new HashMap<>();
  }

  public Set<String> getImports() {
    return imports;
  }

  public Set<String> getAnnotations() {
    return annotations;
  }

  public void addMethod(MethodInfo method) {
    methods.add(method);
  }

  public void addMethodCall(String callerMethod, String calledMethod) {
    methodCalls.computeIfAbsent(callerMethod, k -> new ArrayList<>()).add(calledMethod);
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getPackageName() {
    return packageName;
  }

  public void setPackageName(String packageName) {
    this.packageName = packageName;
  }

  public List<MethodInfo> getMethods() {
    return methods;
  }

  public void setMethods(List<MethodInfo> methods) {
    this.methods = methods;
  }

  public Map<String, List<String>> getMethodCalls() {
    return methodCalls;
  }

  public void setMethodCalls(Map<String, List<String>> methodCalls) {
    this.methodCalls = methodCalls;
  }

  static class MethodInfo {
    String name;
    List<String> parameters;

    public MethodInfo(String name, List<String> parameters) {
      this.name = name;
      this.parameters = parameters;
    }

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public List<String> getParameters() {
      return parameters;
    }

    public void setParameters(List<String> parameters) {
      this.parameters = parameters;
    }
    // Constructor, getters, and setters
  }
}

