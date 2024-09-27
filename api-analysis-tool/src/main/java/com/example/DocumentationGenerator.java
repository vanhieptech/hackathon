package com.example;

import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

public class DocumentationGenerator {

    public void generateDocumentation(List<APIInfo> apis, List<ExternalCallInfo> externalCalls, String fileName) {
        try (FileWriter writer = new FileWriter(fileName)) {
            writer.write("# API Documentation\n\n");

            writer.write("## Exposed APIs\n\n");
            for (APIInfo api : apis) {
                writer.write("### " + api.getMethodName() + "\n\n");
                writer.write("- **HTTP Method:** " + api.getHttpMethod() + "\n");
                writer.write("- **Path:** " + api.getPath() + "\n");
                writer.write("- **Parameters:**\n");
                for (String param : api.getParameters()) {
                    writer.write("  - " + param + "\n");
                }
                writer.write("- **Return Type:** " + api.getReturnType() + "\n\n");
            }

            writer.write("## External API Calls\n\n");
            for (ExternalCallInfo externalCall : externalCalls) {
                writer.write("### " + externalCall.getPurpose() + "\n\n");
                writer.write("- **URL:** " + externalCall.getUrl() + "\n");
                writer.write("- **HTTP Method:** " + externalCall.getHttpMethod() + "\n");
                writer.write("- **Parameters:**\n");
                for (String param : externalCall.getParameters()) {
                    writer.write("  - " + param + "\n");
                }
                writer.write("- **Response Type:** " + externalCall.getResponseType() + "\n\n");
            }
        } catch (IOException e) {
            System.out.println("Error generating documentation: " + e.getMessage());
        }
    }
}