package com.analyzer.service;

import com.itextpdf.text.Document;
import com.itextpdf.text.Paragraph;
import com.itextpdf.text.pdf.PdfWriter;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

@Service
public class DownloadService {

    public Resource generateDownload(String format) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        try {
            switch (format.toLowerCase()) {
                case "pdf":
                    generatePdf(outputStream);
                    break;
                case "csv":
                    generateCsv(outputStream);
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported format: " + format);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate " + format + " file", e);
        }

        return new ByteArrayResource(outputStream.toByteArray());
    }

    private void generatePdf(ByteArrayOutputStream outputStream) throws Exception {
        Document document = new Document();
        PdfWriter.getInstance(document, outputStream);
        document.open();
        document.add(new Paragraph("Analysis Results"));
        // Add more content here
        document.close();
    }

    private void generateCsv(ByteArrayOutputStream outputStream) throws Exception {
        try (CSVPrinter csvPrinter = new CSVPrinter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8), CSVFormat.DEFAULT)) {
            csvPrinter.printRecord("Category", "Value");
            // Add more rows here
        }
    }
}