package com.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

public class DocumentationGenerator {
  private static final Logger logger = LoggerFactory.getLogger(DocumentationGenerator.class);

  public void generateDocumentation(List<APIInfo> apis, List<ExternalCallInfo> externalCalls, String fileName) {
    try (FileWriter writer = new FileWriter(fileName)) {
      writer.write("# API Documentation\n\n");

      writer.write("## Exposed APIs\n\n");
      for (APIInfo api : apis) {
        writer.write("### " + api.getApiName() + "\n\n");
        writer.write("- **Service Name:** " + api.getServiceName() + "\n");
        writer.write("- **HTTP Method:** " + api.getHttpMethod() + "\n");
        writer.write("- **API Endpoint:** " + api.getApiEndpoint() + "\n");
        writer.write("- **Description:** " + api.getDescription() + "\n");
        writer.write("- **Version:** " + api.getVersion() + "\n");
        writer.write("- **Service Dependencies:** " + String.join(", ", api.getServiceDependencies()) + "\n");
        writer.write("- **Return Type:** " + api.getReturnType() + "\n");
        writer.write("- **Parameters:**\n");
        for (APIInfo.ParameterInfo param : api.getParameters()) {
          writer.write("  - " + param.type + " " + param.name +
              (param.annotationType != null ? " (" + param.annotationType + ")" : "") + "\n");
        }
        writer.write("\n");
      }

      writer.write("## External API Calls\n\n");
      for (ExternalCallInfo externalCall : externalCalls) {
        writer.write("### " + externalCall.getMethodName() + "\n\n");
        writer.write("- **Service Name:** " + externalCall.getServiceName() + "\n");
        writer.write("- **URL:** " + externalCall.getUrl() + "\n");
        writer.write("- **HTTP Method:** " + externalCall.getHttpMethod() + "\n");
        writer.write("- **Description:** " + externalCall.getDescription() + "\n");
        writer.write("- **Parameters:**\n");
        for (String param : externalCall.getParameters()) {
          writer.write("  - " + param + "\n");
        }
        writer.write("- **Response Type:** " + externalCall.getResponseType() + "\n\n");
      }

      logger.info("Documentation generated successfully: {}", fileName);
    } catch (IOException e) {
      logger.error("Error generating documentation: " + e.getMessage());
    }
  }
}