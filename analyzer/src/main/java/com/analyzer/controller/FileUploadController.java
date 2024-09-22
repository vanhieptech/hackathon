package com.analyzer.controller;

import com.analyzer.service.AnalyzerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

@Controller
public class FileUploadController {

    @Autowired
    private AnalyzerService analyzerService;

    @Value("${upload.path}")
    private String uploadPath;

    @PostMapping("/upload")
    public String handleFileUpload(@RequestParam("javaProject") MultipartFile javaProject,
                                   @RequestParam("dabFile") MultipartFile dabFile,
                                   RedirectAttributes redirectAttributes) {
        if (javaProject.isEmpty() || dabFile.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Please select both Java project and DAB files to upload.");
            return "redirect:/";
        }

        try {
            // Create the upload directory if it doesn't exist
            Path uploadDir = Paths.get(uploadPath);
            if (!Files.exists(uploadDir)) {
                Files.createDirectories(uploadDir);
            }

            // Save the uploaded files
            String javaProjectPath = saveFile(javaProject, "javaProject");
            String dabFilePath = saveFile(dabFile, "dabFile");

            String analysisId = analyzerService.startAnalysis(javaProjectPath, dabFilePath);
            return "redirect:/results/" + analysisId;
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Error occurred: " + e.getMessage());
            return "redirect:/";
        }
    }

    private String saveFile(MultipartFile file, String prefix) throws IOException {
        String originalFilename = file.getOriginalFilename();
        String filename = prefix + "_" + (originalFilename != null ? originalFilename : "unnamed");
        Path filePath = Paths.get(uploadPath, filename);

        // Ensure the filename is unique
        int count = 1;
        while (Files.exists(filePath)) {
            String newFilename = prefix + "_" + count + "_" + (originalFilename != null ? originalFilename : "unnamed");
            filePath = Paths.get(uploadPath, newFilename);
            count++;
        }

        Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);
        return filePath.toString();
    }

    @GetMapping("/export/csv/{analysisId}")
    public ResponseEntity<String> exportToCsv(@PathVariable String analysisId) {
        try {
            String csvContent = analyzerService.exportToCsv(analysisId);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=analysis_result.csv")
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(csvContent);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error exporting to CSV: " + e.getMessage());
        }
    }

    @GetMapping("/export/pdf/{analysisId}")
    public ResponseEntity<byte[]> exportToPdf(@PathVariable String analysisId) {
        try {
            byte[] pdfContent = analyzerService.exportToPdf(analysisId);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=analysis_result.pdf")
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(pdfContent);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(("Error exporting to PDF: " + e.getMessage()).getBytes());
        }
    }
}