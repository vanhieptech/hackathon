package com.example.service;

import com.example.model.Book;
import com.example.repository.BookRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class BookService {
    private final BookRepository bookRepository;
    private final AuthorServiceClient authorServiceClient;

    public BookService(BookRepository bookRepository, AuthorServiceClient authorServiceClient) {
        this.bookRepository = bookRepository;
        this.authorServiceClient = authorServiceClient;
    }

    public List<Book> getAllBooks() {
        return bookRepository.findAll();
    }

    public Book getBookById(Long id) {
        return bookRepository.findById(id).orElse(null);
    }

    public Book createBook(Book book) {
        return bookRepository.save(book);
    }

    public Book updateBook(Long id, Book bookDetails) {
        Book book = bookRepository.findById(id).orElse(null);
        if (book != null) {
            book.setTitle(bookDetails.getTitle());
            book.setIsbn(bookDetails.getIsbn());
            book.setAuthorId(bookDetails.getAuthorId());
            return bookRepository.save(book);
        }
        return null;
    }

    public void deleteBook(Long id) {
        bookRepository.deleteById(id);
    }

    public String getBookWithAuthor(Long bookId) {
        Book book = getBookById(bookId);
        if (book != null) {
            return authorServiceClient.getAuthor(book.getAuthorId())
                    .map(author -> "Book: " + book.getTitle() + ", Author: " + author.getName())
                    .block();
        }
        return "Book not found";
    }
}
