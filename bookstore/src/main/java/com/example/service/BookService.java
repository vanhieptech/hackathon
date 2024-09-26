package com.example.service;

import com.example.model.Book;
import java.util.List;
import java.util.Optional;

public interface BookService {
    List<Book> getAllBooks();
    Optional<Book> getBookById(Long id);
    Book createBook(Book book);
    Optional<Book> updateBook(Long id, Book bookDetails);
    void deleteBook(Long id);
    Optional<String> getBookWithAuthor(Long bookId);
}