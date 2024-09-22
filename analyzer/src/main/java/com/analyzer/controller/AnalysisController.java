package com.analyzer.controller;

import com.analyzer.model.AnalysisResult;
import com.analyzer.model.AnalysisStatus;
import com.analyzer.service.AnalyzerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
@CrossOrigin(origins = "*")
public class AnalysisController {

    @Autowired
    private AnalyzerService analyzerService;

    @GetMapping("/results")
    public String showResults(Model model) {
        return "results";
    }

    @GetMapping("/results/{analysisId}")
    public String getAnalysisResults(@PathVariable String analysisId, Model model) {
        AnalysisStatus status = analyzerService.getAnalysisStatus(analysisId);
        if (status == AnalysisStatus.COMPLETED) {
            AnalysisResult result = analyzerService.getAnalysisResult(analysisId);
            model.addAttribute("result", result);
            model.addAttribute("overallScore", result.getComparisonResult().getOverallScore());
            model.addAttribute("detailedScores", result.getComparisonResult().getDetailedScores());
            model.addAttribute("discrepancies", result.getComparisonResult().getDiscrepancies());
            return "results";
        } else {
            model.addAttribute("analysisId", analysisId);
            model.addAttribute("message", "Analysis is still in progress. Please wait.");
            model.addAttribute("status", status);
            return "processing";
        }
    }

    @GetMapping("/api/status/{analysisId}")
    public ResponseEntity<String> getAnalysisStatus(@PathVariable String analysisId) {
        AnalysisStatus status = analyzerService.getAnalysisStatus(analysisId);
        return ResponseEntity.ok(status.toString());
    }
}