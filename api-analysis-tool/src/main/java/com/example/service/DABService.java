package com.example.service;

import com.example.model.DABDocument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.FileWriter;
import java.io.IOException;

@Service
public class DABService {

//    @Autowired
//    private ConfluenceReader confluenceReader;
//
//    @Autowired
//    private DABDocumentGenerator documentGenerator;
//
//    public void generateDABDocument(String pageId, String outputPath) throws IOException {
//        String pageContent = confluenceReader.getPageContent(pageId);
//        DABDocument dabDocument = confluenceReader.parsePageContent(pageContent);
//        String htmlContent = documentGenerator.generateHtml(dabDocument);
//
//        try (FileWriter writer = new FileWriter(outputPath)) {
//            writer.write(htmlContent);
//        }
//    }
}