package com.analyzer.util;

import com.analyzer.model.AnalysisResult;
import com.analyzer.model.ApiInfo;
import com.analyzer.model.DatabaseChange;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Map;

@Component
public class CsvExporter {

    public String generateCsvExport(AnalysisResult analysisResult) throws IOException {
        StringWriter stringWriter = new StringWriter();
        try (CSVPrinter csvPrinter = new CSVPrinter(stringWriter, CSVFormat.DEFAULT)) {
            // Write API Information
            csvPrinter.printRecord("API Information");
            csvPrinter.printRecord("Class Name", "Method Name", "Return Type", "Parameters");
            for (ApiInfo apiInfo : analysisResult.getApiInfo()) {
                csvPrinter.printRecord(
                        apiInfo.getClassName(),
                        apiInfo.getMethodName(),
                        apiInfo.getReturnType(),
                        apiInfo.getParameters()
                );
            }
            csvPrinter.println();

            // Write Comparison Results
            csvPrinter.printRecord("Comparison Results");
            csvPrinter.printRecord("Overall Score", analysisResult.getComparisonResult().getOverallScore());
            csvPrinter.printRecord("Detailed Scores");
            for (Map.Entry<String, Double> entry : analysisResult.getComparisonResult().getDetailedScores().entrySet()) {
                csvPrinter.printRecord(entry.getKey(), entry.getValue());
            }
            csvPrinter.printRecord("Discrepancies");
            for (String discrepancy : analysisResult.getComparisonResult().getDiscrepancies()) {
                csvPrinter.printRecord(discrepancy);
            }
            csvPrinter.println();

            // Write Code Quality Metrics
            csvPrinter.printRecord("Code Quality Metrics");
            csvPrinter.printRecord("Metric", "Value");
            csvPrinter.printRecord("Violations", analysisResult.getCodeQualityMetrics().getViolations());
            csvPrinter.printRecord("Complexity", analysisResult.getCodeQualityMetrics().getComplexity());
            csvPrinter.printRecord("Duplication", analysisResult.getCodeQualityMetrics().getDuplication());
            csvPrinter.println();

            // Write Database Changes
            csvPrinter.printRecord("Database Changes");
            csvPrinter.printRecord("Change Type", "Description");
            for (DatabaseChange change : analysisResult.getDatabaseChanges()) {
                csvPrinter.printRecord(change.getType(), change.getDescription());
            }
            csvPrinter.println();

            // Write Sequence Logic
            csvPrinter.printRecord("Sequence Logic");
            for (String logic : analysisResult.getSequenceLogic()) {
                csvPrinter.printRecord(logic);
            }
            csvPrinter.println();

            // Write Exposed APIs
            csvPrinter.printRecord("Exposed APIs");
            for (String api : analysisResult.getExposedApis()) {
                csvPrinter.printRecord(api);
            }
            csvPrinter.println();

            // Write External API Calls
            csvPrinter.printRecord("External API Calls");
            for (String call : analysisResult.getExternalApiCalls()) {
                csvPrinter.printRecord(call);
            }
        }

        return stringWriter.toString();
    }
}