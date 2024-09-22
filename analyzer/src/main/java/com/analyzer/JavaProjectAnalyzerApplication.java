package com.analyzer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
//@EnableCaching
public class JavaProjectAnalyzerApplication {
    public static void main(String[] args) {
        SpringApplication.run(JavaProjectAnalyzerApplication.class, args);
    }
}

