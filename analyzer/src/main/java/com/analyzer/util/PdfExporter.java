package com.analyzer.util;

import com.analyzer.model.AnalysisResult;
import com.analyzer.model.ApiInfo;
import com.analyzer.model.DatabaseChange;
import com.itextpdf.text.*;
import com.itextpdf.text.pdf.PdfPCell;
import com.itextpdf.text.pdf.PdfPTable;
import com.itextpdf.text.pdf.PdfWriter;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

@Component
public class PdfExporter {

    public byte[] generatePdfExport(AnalysisResult analysisResult) throws DocumentException, IOException {
        Document document = new Document();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PdfWriter.getInstance(document, baos);

        document.open();

        addTitle(document, "Analysis Results");

        addSection(document, "API Information");
        addApiInfoTable(document, analysisResult);

        addSection(document, "Comparison Results");
        addComparisonResults(document, analysisResult);

        addSection(document, "Code Quality Metrics");
        addCodeQualityMetrics(document, analysisResult);

        addSection(document, "Database Changes");
        addDatabaseChanges(document, analysisResult);

        addSection(document, "Sequence Logic");
        addListContent(document, analysisResult.getSequenceLogic());

        addSection(document, "Exposed APIs");
        addListContent(document, analysisResult.getExposedApis());

        addSection(document, "External API Calls");
        addListContent(document, analysisResult.getExternalApiCalls());

        document.close();

        return baos.toByteArray();
    }

    private void addTitle(Document document, String title) throws DocumentException {
        Paragraph titleParagraph = new Paragraph(title, new Font(Font.FontFamily.HELVETICA, 18, Font.BOLD));
        titleParagraph.setAlignment(Element.ALIGN_CENTER);
        document.add(titleParagraph);
        document.add(Chunk.NEWLINE);
    }

    private void addSection(Document document, String sectionTitle) throws DocumentException {
        Paragraph sectionParagraph = new Paragraph(sectionTitle, new Font(Font.FontFamily.HELVETICA, 14, Font.BOLD));
        document.add(sectionParagraph);
        document.add(Chunk.NEWLINE);
    }

    private void addApiInfoTable(Document document, AnalysisResult analysisResult) throws DocumentException {
        PdfPTable table = new PdfPTable(4);
        table.setWidthPercentage(100);
        table.addCell("Class Name");
        table.addCell("Method Name");
        table.addCell("Return Type");
        table.addCell("Parameters");

        for (ApiInfo apiInfo : analysisResult.getApiInfo()) {
            table.addCell(apiInfo.getClassName());
            table.addCell(apiInfo.getMethodName());
            table.addCell(apiInfo.getReturnType());
            table.addCell(apiInfo.getParameters());
        }

        document.add(table);
        document.add(Chunk.NEWLINE);
    }

    private void addComparisonResults(Document document, AnalysisResult analysisResult) throws DocumentException {
        document.add(new Paragraph("Overall Score: " + analysisResult.getComparisonResult().getOverallScore()));

        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.addCell("Category");
        table.addCell("Score");

        for (Map.Entry<String, Double> entry : analysisResult.getComparisonResult().getDetailedScores().entrySet()) {
            table.addCell(entry.getKey());
            table.addCell(String.format("%.2f", entry.getValue()));
        }

        document.add(table);

        document.add(new Paragraph("Discrepancies:"));
        for (String discrepancy : analysisResult.getComparisonResult().getDiscrepancies()) {
            document.add(new Paragraph("- " + discrepancy));
        }
        document.add(Chunk.NEWLINE);
    }

    private void addCodeQualityMetrics(Document document, AnalysisResult analysisResult) throws DocumentException {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.addCell("Metric");
        table.addCell("Value");
        table.addCell("Violations");
        table.addCell(String.valueOf(analysisResult.getCodeQualityMetrics().getViolations()));
        table.addCell("Complexity");
        table.addCell(String.valueOf(analysisResult.getCodeQualityMetrics().getComplexity()));
        table.addCell("Duplication");
        table.addCell(String.valueOf(analysisResult.getCodeQualityMetrics().getDuplication()));
        document.add(table);
        document.add(Chunk.NEWLINE);
    }

    private void addDatabaseChanges(Document document, AnalysisResult analysisResult) throws DocumentException {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.addCell("Change Type");
        table.addCell("Description");

        for (DatabaseChange change : analysisResult.getDatabaseChanges()) {
            table.addCell(change.getType());
            table.addCell(change.getDescription());
        }

        document.add(table);
    }

    private void addListContent(Document document, List<String> items) throws DocumentException {
        for (String item : items) {
            document.add(new Paragraph("- " + item));
        }
        document.add(Chunk.NEWLINE);
    }
}