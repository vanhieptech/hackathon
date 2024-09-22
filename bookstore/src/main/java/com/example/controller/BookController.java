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

    @GetMapping
    public List<Book> getAllBooks() {
        // This API is called to retrieve all books
        return bookService.getAllBooks();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Book> getBookById(@PathVariable Long id) {
        // This API is called to retrieve a specific book by its ID
        Book book = bookService.getBookById(id);
        return book != null ? ResponseEntity.ok(book) : ResponseEntity.notFound().build();
    }

    @PostMapping
    public Book createBook(@RequestBody Book book) {
        // This API is called to create a new book
        return bookService.createBook(book);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Book> updateBook(@PathVariable Long id, @RequestBody Book bookDetails) {
        // This API is called to update an existing book
        Book updatedBook = bookService.updateBook(id, bookDetails);
        return updatedBook != null ? ResponseEntity.ok(updatedBook) : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteBook(@PathVariable Long id) {
        // This API is called to delete a book
        bookService.deleteBook(id);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{id}/with-author")
    public ResponseEntity<String> getBookWithAuthor(@PathVariable Long id) {
        // This API is called to retrieve a book with its author information
        // It demonstrates calling the author-service
        String result = bookService.getBookWithAuthor(id);
        return ResponseEntity.ok(result);
    }
}