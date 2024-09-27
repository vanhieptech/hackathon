//package com.example.service;
//
//import com.example.model.ApiInfo;
//import com.example.model.DABDocument;
//import org.jsoup.Jsoup;
//import org.jsoup.nodes.Document;
//import org.jsoup.nodes.Element;
//import org.jsoup.select.Elements;
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.http.HttpEntity;
//import org.springframework.http.HttpHeaders;
//import org.springframework.http.HttpMethod;
//import org.springframework.stereotype.Service;
//import org.springframework.web.client.RestTemplate;
//
//import java.util.ArrayList;
//import java.util.List;
//
//@Service
//public class ConfluenceReader {
//
//    @Value("${confluence.base.url}")
//    private String confluenceBaseUrl;
//
//    @Value("${confluence.api.token}")
//    private String confluenceApiToken;
//
//    private final RestTemplate restTemplate;
//
//    public ConfluenceReader(RestTemplate restTemplate) {
//        this.restTemplate = restTemplate;
//    }
//
//    public String getPageContent(String pageId) {
//        String url = confluenceBaseUrl + "/wiki/rest/api/content/" + pageId + "?expand=body.storage";
//        HttpHeaders headers = new HttpHeaders();
//        headers.set("Authorization", "Bearer " + confluenceApiToken);
//
//        HttpEntity<String> entity = new HttpEntity<>(headers);
//
//        return restTemplate.exchange(url, HttpMethod.GET, entity, String.class).getBody();
//    }
//
//    public DABDocument parsePageContent(String pageContent) {
//        Document doc = Jsoup.parse(pageContent);
//        DABDocument dabDocument = new DABDocument();
//
//        dabDocument.setTitle(doc.select("h1").first().text());
//        dabDocument.setClassDiagram(extractDiagram(doc, "Class Diagram"));
//        dabDocument.setSequenceDiagram(extractDiagram(doc, "Sequence Diagram"));
//        dabDocument.setApiInfo(extractApiInfo(doc));
//        dabDocument.setSequenceLogic(extractSequenceLogic(doc));
//        dabDocument.setExposedApis(extractExposedApis(doc));
//        dabDocument.setExternalApiCalls(extractExternalApiCalls(doc));
//
//        return dabDocument;
//    }
//
//    private String extractDiagram(Document doc, String diagramType) {
//        Element diagramElement = doc.select("pre.plantuml[data-type='" + diagramType + "']").first();
//        return diagramElement != null ? diagramElement.text() : "";
//    }
//
//    private List<ApiInfo> extractApiInfo(Document doc) {
//        List<ApiInfo> apiInfoList = new ArrayList<>();
//        Elements apiInfoElements = doc.select("div.api-info");
//        for (Element apiInfoElement : apiInfoElements) {
//            ApiInfo apiInfo = new ApiInfo();
//            apiInfo.setClassName(apiInfoElement.select("h3.class-name").text());
//            apiInfo.setMethodName(apiInfoElement.select("div.method-name").text());
//            apiInfo.setReturnType(apiInfoElement.select("div.return-type").text());
//            apiInfo.setParameters(apiInfoElement.select("div.parameters").text());
//            apiInfoList.add(apiInfo);
//        }
//        return apiInfoList;
//    }
//
//    private List<String> extractSequenceLogic(Document doc) {
//        List<String> sequenceLogic = new ArrayList<>();
//        Element sequenceLogicElement = doc.select("div.sequence-logic").first();
//        if (sequenceLogicElement != null) {
//            for (Element step : sequenceLogicElement.children()) {
//                sequenceLogic.add(step.text());
//            }
//        }
//        return sequenceLogic;
//    }
//
//    private List<String> extractExposedApis(Document doc) {
//        List<String> exposedApis = new ArrayList<>();
//        Elements exposedApiElements = doc.select("div.exposed-api");
//        for (Element exposedApiElement : exposedApiElements) {
//            exposedApis.add(exposedApiElement.text());
//        }
//        return exposedApis;
//    }
//
//    private List<String> extractExternalApiCalls(Document doc) {
//        List<String> externalApiCalls = new ArrayList<>();
//        Elements externalApiCallElements = doc.select("div.external-api-call");
//        for (Element externalApiCallElement : externalApiCallElements) {
//            externalApiCalls.add(externalApiCallElement.text());
//        }
//        return externalApiCalls;
//    }
//}