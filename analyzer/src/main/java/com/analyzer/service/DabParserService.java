package com.analyzer.service;

import com.analyzer.model.ApiInfo;
import com.analyzer.model.UmlDiagram;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class DabParserService {

    public String extractDabContent(InputStream inputStream) throws IOException {
        return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    }

    public List<UmlDiagram> parseDabDiagrams(String dabContent) {
        List<UmlDiagram> diagrams = new ArrayList<>();
        Document doc = Jsoup.parse(dabContent);

        Elements diagramElements = doc.select("pre.plantuml");
        for (Element diagramElement : diagramElements) {
            String diagramType = diagramElement.attr("data-type");
            String diagramContent = diagramElement.text();
            String id = UUID.randomUUID().toString();
            diagrams.add(new UmlDiagram(id, diagramType, diagramContent));
        }

        return diagrams;
    }

    public List<ApiInfo> parseDabApiInfo(String dabContent) {
        List<ApiInfo> apiInfoList = new ArrayList<>();
        Document doc = Jsoup.parse(dabContent);

        Elements apiElements = doc.select("div.api-info");
        for (Element apiElement : apiElements) {
            String className = apiElement.select(".class-name").text();
            String methodName = apiElement.select(".method-name").text();
            String returnType = apiElement.select(".return-type").text();
            String parameters = apiElement.select(".parameters").text();

            apiInfoList.add(new ApiInfo(className, methodName, returnType, parameters));
        }

        return apiInfoList;
    }

    public List<String> parseSequenceDiagramLogic(String dabContent) {
        List<String> sequenceLogic = new ArrayList<>();
        Document doc = Jsoup.parse(dabContent);

        Elements logicElements = doc.select("div.sequence-logic");
        for (Element logicElement : logicElements) {
            sequenceLogic.add(logicElement.text());
        }

        return sequenceLogic;
    }

    public List<String> parseExposedApis(String dabContent) {
        List<String> exposedApis = new ArrayList<>();
        Document doc = Jsoup.parse(dabContent);

        Elements apiElements = doc.select("div.exposed-api");
        for (Element apiElement : apiElements) {
            exposedApis.add(apiElement.text());
        }

        return exposedApis;
    }

    public List<String> parseExternalApiCalls(String dabContent) {
        List<String> externalApiCalls = new ArrayList<>();
        Document doc = Jsoup.parse(dabContent);

        Elements callElements = doc.select("div.external-api-call");
        for (Element callElement : callElements) {
            externalApiCalls.add(callElement.text());
        }

        return externalApiCalls;
    }
}