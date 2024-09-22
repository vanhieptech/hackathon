package com.analyzer.service;

import com.analyzer.model.ApiInfo;
import com.analyzer.model.UmlDiagram;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class ApiExtractorService {

    public List<ApiInfo> extractApiInfo(Map<String, byte[]> projectFiles) {
        List<ApiInfo> apiInfoList = new ArrayList<>();

        for (Map.Entry<String, byte[]> entry : projectFiles.entrySet()) {
            if (entry.getKey().endsWith(".java")) {
                try {
                    CompilationUnit cu = StaticJavaParser.parse(new ByteArrayInputStream(entry.getValue()));
                    apiInfoList.addAll(processCompilationUnit(cu));
                } catch (Exception e) {
                    System.err.println("Error processing file " + entry.getKey() + ": " + e.getMessage());
                }
            }
        }

        return apiInfoList;
    }

    private List<ApiInfo> processCompilationUnit(CompilationUnit cu) {
        List<ApiInfo> apiInfoList = new ArrayList<>();

        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(coid -> {
            if (coid.isPublic()) {
                String className = coid.getNameAsString();
                coid.getMethods().stream()
                        .filter(MethodDeclaration::isPublic)
                        .forEach(method -> {
                            ApiInfo apiInfo = new ApiInfo(
                                    className,
                                    method.getNameAsString(),
                                    method.getType().asString(),
                                    method.getParameters().toString()
                            );
                            apiInfoList.add(apiInfo);
                        });
            }
        });

        return apiInfoList;
    }
}