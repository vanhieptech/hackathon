package com.analyzer.controller;

import com.analyzer.service.DownloadService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class DownloadController {

    @Autowired
    private DownloadService downloadService;

    @GetMapping("/download")
    public ResponseEntity<Resource> downloadResults(@RequestParam("format") String format) {
        Resource resource = downloadService.generateDownload(format);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"analysis_results." + format + "\"")
                .body(resource);
    }
}