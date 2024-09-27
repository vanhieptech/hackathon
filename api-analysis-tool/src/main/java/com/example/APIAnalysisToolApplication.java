package com.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
//@ComponentScan(basePackages = {"com.example.service", "com.example.config"})
public class APIAnalysisToolApplication {
    public static void main(String[] args) {
        SpringApplication.run(APIAnalysisToolApplication.class, args);
    }
}