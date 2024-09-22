package com.analyzer.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Service
public class ProgressService {

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    public void sendProgressUpdate(String analysisId, String message, int progressPercentage) {
        ProgressUpdate update = new ProgressUpdate(message, progressPercentage);
        messagingTemplate.convertAndSend("/topic/progress/" + analysisId, update);
    }
}

class ProgressUpdate {
    private String message;
    private int progressPercentage;

    public ProgressUpdate(String message, int progressPercentage) {
        this.message = message;
        this.progressPercentage = progressPercentage;
    }

    // Getters and setters
    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public int getProgressPercentage() {
        return progressPercentage;
    }

    public void setProgressPercentage(int progressPercentage) {
        this.progressPercentage = progressPercentage;
    }
}