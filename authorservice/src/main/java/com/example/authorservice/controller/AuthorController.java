package com.example.authorservice.controller;

import com.example.authorservice.model.Author;
import com.example.authorservice.service.AuthorService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/authors")
public class AuthorController {
    private final AuthorService authorService;

    public AuthorController(AuthorService authorService) {
        this.authorService = authorService;
    }

    @GetMapping
    public List<Author> getAllAuthors() {
        // This API is called to retrieve all authors
        return authorService.getAllAuthors();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Author> getAuthorById(@PathVariable Long id) {
        // This API is called to retrieve a specific author by their ID
        Author author = authorService.getAuthorById(id);
        return author != null ? ResponseEntity.ok(author) : ResponseEntity.notFound().build();
    }

    @PostMapping
    public Author createAuthor(@RequestBody Author author) {
        // This API is called to create a new author
        return authorService.createAuthor(author);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Author> updateAuthor(@PathVariable Long id, @RequestBody Author authorDetails) {
        // This API is called to update an existing author
        Author updatedAuthor = authorService.updateAuthor(id, authorDetails);
        return updatedAuthor != null ? ResponseEntity.ok(updatedAuthor) : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteAuthor(@PathVariable Long id) {
        // This API is called to delete an author
        authorService.deleteAuthor(id);
        return ResponseEntity.ok().build();
    }
}
