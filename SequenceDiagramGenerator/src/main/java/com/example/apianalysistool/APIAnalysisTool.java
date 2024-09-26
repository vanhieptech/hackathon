package com.example.apianalysistool;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class APIAnalysisTool {

    private final APIScanner apiScanner;
    private final ExternalCallScanner externalCallScanner;
    private final UMLGenerator umlGenerator;
    private final DocumentationGenerator docGenerator;
    private final CSVExporter csvExporter;

    public APIAnalysisTool() {
        this.apiScanner = new APIScanner();
        this.externalCallScanner = new ExternalCallScanner();
        this.umlGenerator = new UMLGenerator();
        this.docGenerator = new DocumentationGenerator();
        this.csvExporter = new CSVExporter();
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Please provide the path to the Java project or JAR file.");
            return;
        }

        String projectPath = args[0];
        APIAnalysisTool tool = new APIAnalysisTool();
        try {
            tool.analyzeProject(projectPath);
        } catch (Exception e) {
            System.out.println("An error occurred during analysis: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void analyzeProject(String projectPath) throws IOException {
        File projectFile = new File(projectPath);
        if (!projectFile.exists()) {
            throw new IOException("The specified path does not exist: " + projectPath);
        }

        List<ClassNode> allClasses = new ArrayList<>();

        if (projectFile.isDirectory()) {
            buildProject(projectFile);
            allClasses.addAll(analyzeDirectory(projectFile));
        } else if (projectPath.endsWith(".jar")) {
            allClasses.addAll(analyzeJar(projectFile));
        } else {
            throw new IOException("Unsupported file type. Please provide a directory or JAR file.");
        }

        List<APIInfo> apis = apiScanner.findExposedAPIs(allClasses);
        List<ExternalCallInfo> externalCalls = externalCallScanner.findExternalCalls(allClasses);

        umlGenerator.generateDiagrams(apis, externalCalls);
        docGenerator.generateDocumentation(apis, externalCalls);
        csvExporter.exportToCSV(apis, externalCalls);

        System.out.println("Analysis complete. Check the output directory for results.");
    }

    private void buildProject(File projectDirectory) throws IOException {
        String buildTool = detectBuildTool(projectDirectory);
        ProcessBuilder processBuilder = new ProcessBuilder();
        processBuilder.directory(projectDirectory);

        if ("maven".equals(buildTool)) {
            processBuilder.command("mvn", "clean", "install", "-DskipTests");
        } else if ("gradle".equals(buildTool)) {
            processBuilder.command("./gradlew", "clean", "build", "-x", "test");
        } else {
            throw new IOException("Unsupported build tool. Only Maven and Gradle are supported.");
        }

        Process process = processBuilder.start();
        try {
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("Build failed with exit code: " + exitCode);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Build process was interrupted", e);
        }
    }

    private String detectBuildTool(File projectDirectory) {
        if (new File(projectDirectory, "pom.xml").exists()) {
            return "maven";
        } else if (new File(projectDirectory, "build.gradle").exists()) {
            return "gradle";
        } else {
            return "unknown";
        }
    }

    private List<ClassNode> analyzeDirectory(File directory) throws IOException {
        List<ClassNode> classes = new ArrayList<>();
        Path targetDir = directory.toPath().resolve("target/classes");
        if (Files.exists(targetDir)) {
            Files.walk(targetDir)
                    .filter(path -> path.toString().endsWith(".class"))
                    .forEach(path -> classes.add(analyzeClass(path)));
        }
        return classes;
    }

    private List<ClassNode> analyzeJar(File jarFile) throws IOException {
        List<ClassNode> classes = new ArrayList<>();
        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.isDirectory() && entry.getName().endsWith(".class")) {
                    classes.add(analyzeJarEntry(jar, entry));
                }
            }
        }
        return classes;
    }

    private ClassNode analyzeClass(Path classFilePath) {
        try {
            byte[] classBytes = Files.readAllBytes(classFilePath);
            return createClassNode(classBytes);
        } catch (IOException e) {
            System.out.println("Error reading class file: " + e.getMessage());
            return null;
        }
    }

    private ClassNode analyzeJarEntry(JarFile jar, JarEntry entry) throws IOException {
        byte[] classBytes = jar.getInputStream(entry).readAllBytes();
        return createClassNode(classBytes);
    }

    private ClassNode createClassNode(byte[] classBytes) {
        ClassReader reader = new ClassReader(classBytes);
        ClassNode classNode = new ClassNode();
        reader.accept(classNode, 0);
        return classNode;
    }
}