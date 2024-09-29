package com.example.service;

import com.example.dto.AuthorDTO;
import com.example.exception.AuthorNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.http.HttpStatus;
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
        .onStatus(HttpStatus::is4xxClientError, response -> {
          if (response.statusCode() == HttpStatus.NOT_FOUND) {
            return Mono.error(new AuthorNotFoundException("Author not found with id: " + authorId));
          }
          return Mono.error(new RuntimeException("Client error: " + response.statusCode()));
        })
        .onStatus(HttpStatus::is5xxServerError,
            response -> Mono.error(new RuntimeException("Server error: " + response.statusCode())))
        .bodyToMono(AuthorDTO.class);
  }
}