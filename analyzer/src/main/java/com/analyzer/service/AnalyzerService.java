package com.analyzer.service;

import com.analyzer.model.*;
import com.analyzer.util.CsvExporter;
import com.analyzer.util.PdfExporter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class AnalyzerService {

    @Autowired
    private UmlGeneratorService umlGeneratorService;

    @Autowired
    private ApiExtractorService apiExtractorService;

    @Autowired
    private DabParserService dabParserService;

    @Autowired
    private ComparisonService comparisonService;

    @Autowired
    private CodeQualityService codeQualityService;

    @Autowired
    private LiquibaseChangeExtractor liquibaseChangeExtractor;

    @Autowired
    private CsvExporter csvExporter;

    @Autowired
    private PdfExporter pdfExporter;

    @Autowired
    private ProgressService progressService;

    private final Map<String, AnalysisStatus> analysisStatuses = new ConcurrentHashMap<>();
    private final Map<String, AnalysisResult> analysisResults = new ConcurrentHashMap<>();

    public String startAnalysis(String javaProjectPath, String dabFilePath) throws IOException {
        String analysisId = UUID.randomUUID().toString();
        analysisStatuses.put(analysisId, AnalysisStatus.IN_PROGRESS);
        new Thread(() -> performAnalysis(javaProjectPath, dabFilePath, analysisId)).start();
        return analysisId;
    }

    public AnalysisStatus getAnalysisStatus(String analysisId) {
        return analysisStatuses.getOrDefault(analysisId, AnalysisStatus.NOT_FOUND);
    }

    @Cacheable(value = "analysisResults", key = "#analysisId")
    public AnalysisResult getAnalysisResult(String analysisId) {
        // This method will return cached results, so we don't implement it here
        return analysisResults.get(analysisId);
    }

    private void performAnalysis(String javaProjectPath, String dabFilePath, String analysisId) {
        try {
            sendProgressUpdate(analysisId, "Starting analysis...", 0);

            // Read Java project
            Map<String, byte[]> projectFiles = readZipFile(javaProjectPath);

            sendProgressUpdate(analysisId, "Generating UML diagrams...", 10);
            List<UmlDiagram> umlDiagrams = umlGeneratorService.generateUmlDiagrams(projectFiles);

            sendProgressUpdate(analysisId, "Extracting API information...", 30);
            List<ApiInfo> apiInfo = apiExtractorService.extractApiInfo(projectFiles);

            sendProgressUpdate(analysisId, "Analyzing code quality...", 50);
            CodeQualityMetrics codeQualityMetrics = codeQualityService.analyzeCodeQuality(projectFiles);

            sendProgressUpdate(analysisId, "Extracting database changes...", 60);
            List<DatabaseChange> databaseChanges = liquibaseChangeExtractor.extractChanges(projectFiles);

            // Parse DAB document
            sendProgressUpdate(analysisId, "Parsing DAB document...", 70);
            String dabContent = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(dabFilePath)));
            List<UmlDiagram> dabDiagrams = dabParserService.parseDabDiagrams(dabContent);
            List<ApiInfo> dabApiInfo = dabParserService.parseDabApiInfo(dabContent);
            List<String> sequenceLogic = dabParserService.parseSequenceDiagramLogic(dabContent);
            List<String> exposedApis = dabParserService.parseExposedApis(dabContent);
            List<String> externalApiCalls = dabParserService.parseExternalApiCalls(dabContent);

            sendProgressUpdate(analysisId, "Comparing extracted information with DAB...", 90);
            ComparisonResult comparisonResult = comparisonService.compare(umlDiagrams, dabDiagrams, apiInfo, dabApiInfo);

            // Create AnalysisResult object
            AnalysisResult result = new AnalysisResult(
                    analysisId,
                    umlDiagrams,
                    apiInfo,
                    comparisonResult,
                    codeQualityMetrics,
                    databaseChanges,
                    sequenceLogic,
                    exposedApis,
                    externalApiCalls
            );

            // Cache the result
            cacheAnalysisResult(analysisId, result);
            analysisResults.put(analysisId, result);
            analysisStatuses.put(analysisId, AnalysisStatus.COMPLETED);
            sendProgressUpdate(analysisId, "Analysis completed.", 100);

        } catch (Exception e) {
            analysisStatuses.put(analysisId, AnalysisStatus.ERROR);
            sendProgressUpdate(analysisId, "Error occurred during analysis: " + e.getMessage(), -1);
        }
    }

    public String exportToCsv(String analysisId) throws IOException {
        AnalysisResult result = getAnalysisResult(analysisId);
        if (result == null) {
            throw new RuntimeException("Analysis result not found for ID: " + analysisId);
        }
        return csvExporter.generateCsvExport(result);
    }

    public byte[] exportToPdf(String analysisId) throws Exception {
        AnalysisResult result = getAnalysisResult(analysisId);
        if (result == null) {
            throw new RuntimeException("Analysis result not found for ID: " + analysisId);
        }
        return pdfExporter.generatePdfExport(result);
    }

    private Map<String, byte[]> readZipFile(String zipFilePath) throws IOException {
        Map<String, byte[]> fileContents = new HashMap<>();
        try (ZipInputStream zipInputStream = new ZipInputStream(new FileInputStream(zipFilePath))) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buffer = new byte[1024];
                    int len;
                    while ((len = zipInputStream.read(buffer)) > 0) {
                        bos.write(buffer, 0, len);
                    }
                    fileContents.put(entry.getName(), bos.toByteArray());
                }
            }
        }
        return fileContents;
    }

    private void sendProgressUpdate(String analysisId, String message, int progressPercentage) {
        AnalysisStatus status = progressPercentage == 100 ? AnalysisStatus.COMPLETED :
                progressPercentage == -1 ? AnalysisStatus.ERROR :
                        AnalysisStatus.IN_PROGRESS;
        analysisStatuses.put(analysisId, status);
        progressService.sendProgressUpdate(analysisId, message, progressPercentage);
    }

    private void cacheAnalysisResult(String analysisId, AnalysisResult result) {
        // Implement caching logic here. This could be using Spring's @Cacheable
        // or a custom caching solution.
    }
}