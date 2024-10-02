// package com.example;

// import org.apache.commons.csv.CSVFormat;
// import org.apache.commons.csv.CSVPrinter;
// import org.slf4j.Logger;
// import org.slf4j.LoggerFactory;

// import com.example.model.APIInfo;

// import java.io.FileWriter;
// import java.io.IOException;
// import java.util.List;

// public class CSVGenerator {
// private static final Logger logger =
// LoggerFactory.getLogger(CSVGenerator.class);

// public String generateCSV(APIInfo apiInventory, String projectName) {
// String fileName = projectName + "_api_inventory.csv";
// try (FileWriter writer = new FileWriter(fileName)) {
// generateDefaultFormat(writer, apiInventory);
// logger.info("CSV file generated successfully: {}", fileName);
// return fileName;
// } catch (IOException e) {
// logger.error("Error generating CSV file: {}", e.getMessage());
// return null;
// }
// }

// private void generateDefaultFormat(FileWriter writer, APIInfo apiInventory)
// throws IOException {
// CSVPrinter csvPrinter = new CSVPrinter(writer, CSVFormat.DEFAULT
// .withHeader("Type", "Service Name", "API Name", "HTTP Method", "API
// Endpoint", "Description", "Version",
// "Service Dependency", "Return Type", "Parameters"));

// for (APIInfo.ExposedAPI api : apiInventory.getExposedApis()) {
// csvPrinter.printRecord(
// "Internal",
// apiInventory.getServiceName(),
// api.getServiceMethod(),
// api.getHttpMethod(),
// api.getPath(),
// "N/A",
// "N/A",
// api.getServiceClassName(),
// api.getReturnType(),
// formatParameters(api.getParameters()));
// }

// csvPrinter.flush();
// }

// private String formatParameters(List<APIInfo.ParameterInfo> parameters) {
// if (parameters == null) {
// return "";
// }
// return parameters.stream()
// .map((APIInfo.ParameterInfo param) -> param.getType() + " " +
// param.getName())
// .collect(java.util.stream.Collectors.joining(", "));
// }
// }