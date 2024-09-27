package com.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

public class CSVGenerator {
    private static final Logger logger = LoggerFactory.getLogger(CSVGenerator.class);

    public void generateCSV(List<APIInfo> apiInventory, List<ExternalCallInfo> externalCalls, String fileName, String format) {
        try (FileWriter writer = new FileWriter(fileName)) {
            switch (format.toLowerCase()) {
                case "default":
                    generateDefaultFormat(writer, apiInventory, externalCalls);
                    break;
                case "dynatrace":
                    generateDynatraceFormat(writer, apiInventory, externalCalls);
                    break;
                case "appdynamics":
                    generateAppDynamicsFormat(writer, apiInventory, externalCalls);
                    break;
                default:
                    logger.warn("Unknown format '{}'. Using default format.", format);
                    generateDefaultFormat(writer, apiInventory, externalCalls);
            }

            logger.info("CSV file generated successfully: {}", fileName);
        } catch (IOException e) {
            logger.error("Error generating CSV file: {}", e.getMessage());
        }
    }

    private void generateDefaultFormat(FileWriter writer, List<APIInfo> apiInventory, List<ExternalCallInfo> externalCalls) throws IOException {
        writer.append("Type,HTTP Method,Path,Method Name,Return Type,Parameters,External URL\n");

        for (APIInfo api : apiInventory) {
            writer.append("Internal,")
                    .append(api.getHttpMethod() != null ? api.getHttpMethod() : "N/A").append(",")
                    .append(api.getPath() != null ? api.getPath() : "N/A").append(",")
                    .append(api.getMethodName()).append(",")
                    .append(api.getReturnType()).append(",")
                    .append(String.join(";", api.getParameters())).append(",")
                    .append("N/A")
                    .append("\n");
        }

        for (ExternalCallInfo externalCall : externalCalls) {
            writer.append("External,")
                    .append(externalCall.getHttpMethod()).append(",")
                    .append("N/A,")
                    .append(externalCall.getPurpose()).append(",")
                    .append(externalCall.getResponseType() != null ? externalCall.getResponseType() : "N/A").append(",")
                    .append(String.join(";", externalCall.getParameters())).append(",")
                    .append(externalCall.getUrl())
                    .append("\n");
        }
    }

    private void generateDynatraceFormat(FileWriter writer, List<APIInfo> apiInventory, List<ExternalCallInfo> externalCalls) throws IOException {
        // Implement Dynatrace-specific CSV format
    }

    private void generateAppDynamicsFormat(FileWriter writer, List<APIInfo> apiInventory, List<ExternalCallInfo> externalCalls) throws IOException {
        // Implement AppDynamics-specific CSV format
    }
}