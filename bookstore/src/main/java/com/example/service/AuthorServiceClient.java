package com.example.service;
import com.example.dto.AuthorDTO;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
public class AuthorServiceClient {
    private final WebClient webClient;

    public AuthorServiceClient(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.baseUrl("http://localhost:8081").build();
    }

    public Mono<AuthorDTO> getAuthor(Long authorId) {
        return webClient.get()
                .uri("/api/authors/{id}", authorId)
                .retrieve()
                .bodyToMono(AuthorDTO.class);
    }
}