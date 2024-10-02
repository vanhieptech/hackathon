package com.example.controller;

import com.example.model.Book;
import com.example.service.BookService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/books")
public class BookController {
    private final BookService bookService;

    public BookController(BookService bookService) {
        this.bookService = bookService;
    }

    @GetMapping("/{id}/with-author")
    public ResponseEntity<String> getBookWithAuthor(@PathVariable Long id) {
        return bookService.getBookWithAuthor(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}