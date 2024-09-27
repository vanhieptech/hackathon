package com.example.service;

import com.example.model.DABDocument;
import com.example.model.ApiInfo;
import org.springframework.stereotype.Service;

@Service
public class DABDocumentGenerator {

    public String generateHtml(DABDocument document) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n")
                .append("<html lang=\"en\">\n")
                .append("<head>\n")
                .append("    <meta charset=\"UTF-8\">\n")
                .append("    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n")
                .append("    <title>").append(document.getTitle()).append("</title>\n")
                .append("</head>\n")
                .append("<body>\n")
                .append("    <h1>").append(document.getTitle()).append("</h1>\n\n")
                .append("    <h2>1. Class Diagram</h2>\n")
                .append("    <pre class=\"plantuml\" data-type=\"Class Diagram\">\n")
                .append(document.getClassDiagram()).append("\n")
                .append("    </pre>\n\n")
                .append("    <h2>2. Sequence Diagram</h2>\n")
                .append("    <pre class=\"plantuml\" data-type=\"Sequence Diagram\">\n")
                .append(document.getSequenceDiagram()).append("\n")
                .append("    </pre>\n\n")
                .append("    <h2>3. API Information</h2>\n");

        for (ApiInfo apiInfo : document.getApiInfo()) {
            html.append("    <div class=\"api-info\">\n")
                    .append("        <h3 class=\"class-name\">").append(apiInfo.getClassName()).append("</h3>\n")
                    .append("        <div class=\"method-name\">").append(apiInfo.getMethodName()).append("</div>\n")
                    .append("        <div class=\"return-type\">").append(apiInfo.getReturnType()).append("</div>\n")
                    .append("        <div class=\"parameters\">").append(apiInfo.getParameters()).append("</div>\n")
                    .append("    </div>\n");
        }

        html.append("\n    <h2>4. Sequence Logic</h2>\n")
                .append("    <div class=\"sequence-logic\">\n");
        for (String step : document.getSequenceLogic()) {
            html.append("        ").append(step).append("\n");
        }
        html.append("    </div>\n\n")
                .append("    <h2>5. Exposed APIs</h2>\n");
        for (String api : document.getExposedApis()) {
            html.append("    <div class=\"exposed-api\">").append(api).append("</div>\n");
        }

        html.append("\n    <h2>6. External API Calls</h2>\n");
        for (String call : document.getExternalApiCalls()) {
            html.append("    <div class=\"external-api-call\">").append(call).append("</div>\n");
        }

        html.append("</body>\n")
                .append("</html>");

        return html.toString();
    }
}