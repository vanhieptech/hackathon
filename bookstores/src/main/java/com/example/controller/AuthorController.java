package com.example.controller;

import com.example.api.AuthorsApi;
import com.example.model.Author;
import com.example.service.AuthorServiceClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
public class AuthorController implements AuthorsApi {

  private final AuthorServiceClient authorServiceClient;

  public AuthorController(AuthorServiceClient authorServiceClient) {
    this.authorServiceClient = authorServiceClient;
  }

  @Override
  public Mono<ResponseEntity<Author>> getAuthorById(Long id, ServerWebExchange exchange) {
    return authorServiceClient.getAuthor(id)
        .map(authorDTO -> {
          Author author = new Author();
          author.setId(authorDTO.getId());
          author.setName(authorDTO.getName());
          author.setBirthDate(authorDTO.getBirthDate());
          return ResponseEntity.ok(author);
        })
        .onErrorResume(throwable -> Mono.just(ResponseEntity.notFound().build()));
  }
}