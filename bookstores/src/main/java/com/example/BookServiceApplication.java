package com.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.web.reactive.function.client.WebClient;

@SpringBootApplication
@ComponentScan(basePackages = {"com.example", "com.example.api"})
public class BookServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(BookServiceApplication.class, args);
    }

    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }
}