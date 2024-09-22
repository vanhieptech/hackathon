package com.analyzer.service;

import com.analyzer.model.CodeQualityMetrics;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;


@Service
public class CodeQualityService {

    public CodeQualityMetrics analyzeCodeQuality(Map<String, byte[]> projectFiles) {
        int totalViolations = 0;
        int totalComplexity = 0;
        int totalMethods = 0;
        int totalLines = 0;
        int duplicateLines = 0;

        for (Map.Entry<String, byte[]> entry : projectFiles.entrySet()) {
            if (entry.getKey().endsWith(".java")) {
                try {
                    CompilationUnit cu = StaticJavaParser.parse(new ByteArrayInputStream(entry.getValue()));
                    List<MethodDeclaration> methods = cu.findAll(MethodDeclaration.class);

                    totalMethods += methods.size();
                    totalLines += cu.getEnd().get().line;

                    for (MethodDeclaration method : methods) {
                        totalComplexity += calculateCyclomaticComplexity(method);
                    }

                    totalViolations += checkViolations(cu);
                    duplicateLines += checkDuplication(cu);
                } catch (Exception e) {
                    System.err.println("Error processing file " + entry.getKey() + ": " + e.getMessage());
                }
            }
        }

        double averageComplexity = totalMethods > 0 ? (double) totalComplexity / totalMethods : 0;
        double duplicationRatio = totalLines > 0 ? (double) duplicateLines / totalLines : 0;

        return new CodeQualityMetrics(totalViolations, averageComplexity, duplicationRatio);
    }

    private int calculateCyclomaticComplexity(MethodDeclaration method) {
        // This is a simplified calculation. A real implementation would be more comprehensive.
        int complexity = 1; // base complexity
        complexity += method.findAll(com.github.javaparser.ast.stmt.IfStmt.class).size();
        complexity += method.findAll(com.github.javaparser.ast.stmt.ForStmt.class).size();
        complexity += method.findAll(com.github.javaparser.ast.stmt.WhileStmt.class).size();
        complexity += method.findAll(com.github.javaparser.ast.stmt.SwitchStmt.class).size();
        complexity += method.findAll(com.github.javaparser.ast.expr.ConditionalExpr.class).size();
        return complexity;
    }

    private int checkViolations(CompilationUnit cu) {
        // This is a placeholder. A real implementation would check for specific code style violations.
        return 0;
    }

    private int checkDuplication(CompilationUnit cu) {
        // This is a placeholder. A real implementation would check for code duplication.
        return 0;
    }
}