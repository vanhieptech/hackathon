package com.example.apianalysistool;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

public class CSVExporter {

    public void exportToCSV(List<APIInfo> apis, List<ExternalCallInfo> externalCalls) {
        try (CSVPrinter printer = new CSVPrinter(new FileWriter("api_analysis.csv"), CSVFormat.DEFAULT)) {
            printer.printRecord("Type", "Name", "HTTP Method", "Path/URL", "Parameters", "Return Type/Purpose");

            for (APIInfo api : apis) {
                printer.printRecord(
                        "Internal API",
                        api.getMethodName(),
                        api.getHttpMethod(),
                        api.getPath(),
                        String.join(", ", api.getParameters()),
                        api.getReturnType()
                );
            }

            for (ExternalCallInfo externalCall : externalCalls) {
                printer.printRecord(
                        "External API",
                        externalCall.getPurpose(),
                        externalCall.getHttpMethod(),
                        externalCall.getUrl(),
                        String.join(", ", externalCall.getParameters()),
                        "N/A"
                );
            }
        } catch (IOException e) {
            System.out.println("Error exporting to CSV: " + e.getMessage());
        }
    }
}