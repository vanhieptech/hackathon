package com.example;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

public class CSVGenerator {
  private static final Logger logger = LoggerFactory.getLogger(CSVGenerator.class);

  public void generateCSV(List<APIInfo> apiInventory, List<ExternalCallInfo> externalCalls, String fileName,
      String format) {
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

  private void generateDefaultFormat(FileWriter writer, List<APIInfo> apiInventory,
      List<ExternalCallInfo> externalCalls) throws IOException {
    CSVPrinter csvPrinter = new CSVPrinter(writer, CSVFormat.DEFAULT
        .withHeader("Type", "Service Name", "API Name", "HTTP Method", "API Endpoint", "Description", "Version",
            "Service Dependency", "Return Type", "Parameters"));

    for (APIInfo api : apiInventory) {
      csvPrinter.printRecord(
          "Internal",
          api.getServiceName(),
          api.getApiName(),
          api.getHttpMethod(),
          api.getApiEndpoint(),
          api.getDescription(),
          api.getVersion(),
          api.getServiceDependencies() != null ? String.join(";", api.getServiceDependencies()) : "",
          api.getReturnType(),
          formatParameters(api.getParameters()));
    }

    for (ExternalCallInfo externalCall : externalCalls) {
      csvPrinter.printRecord(
          "External",
          externalCall.getServiceName(),
          externalCall.getMethodName(),
          externalCall.getHttpMethod(),
          externalCall.getUrl() != null ? externalCall.getUrl() : "Unknown",
          externalCall.getDescription(),
          "N/A",
          "N/A",
          externalCall.getResponseType(),
          String.join(";", externalCall.getParameters()));
    }

    csvPrinter.flush();
  }

  private String formatParameters(List<APIInfo.ParameterInfo> parameters) {
    if (parameters == null) {
      return "";
    }
    return parameters.stream()
        .map(param -> param.type + " " + param.name + " ("
            + (param.annotationType != null ? param.annotationType : "N/A") + ")")
        .collect(java.util.stream.Collectors.joining(", "));
  }

  private void generateDynatraceFormat(FileWriter writer, List<APIInfo> apiInventory,
      List<ExternalCallInfo> externalCalls) throws IOException {
    // Implement Dynatrace-specific CSV format
  }

  private void generateAppDynamicsFormat(FileWriter writer, List<APIInfo> apiInventory,
      List<ExternalCallInfo> externalCalls) throws IOException {
    // Implement AppDynamics-specific CSV format
  }
}