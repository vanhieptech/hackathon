package com.example.service.impl;

import com.example.exception.AuthorNotFoundException;
import com.example.model.Book;
import com.example.repository.BookRepository;
import com.example.dto.AuthorDTO;
import com.example.service.AuthorServiceClient;
import com.example.service.BookService;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.List;
import java.util.Optional;

@Service
public class BookServiceImpl implements BookService {
  private final BookRepository bookRepository;
  private final AuthorServiceClient authorServiceClient;

  public BookServiceImpl(BookRepository bookRepository, AuthorServiceClient authorServiceClient) {
    this.bookRepository = bookRepository;
    this.authorServiceClient = authorServiceClient;
  }

  @Override
  public List<Book> getAllBooks() {
    return bookRepository.findAll();
  }

  @Override
  public Optional<Book> getBookById(Long id) {
    return bookRepository.findById(id);
  }

  @Override
  public Book createBook(Book book) {
    return bookRepository.save(book);
  }

  @Override
  public Optional<Book> updateBook(Long id, Book bookDetails) {
    return bookRepository.findById(id)
        .map(book -> {
          book.setTitle(bookDetails.getTitle());
          book.setIsbn(bookDetails.getIsbn());
          book.setAuthorId(bookDetails.getAuthorId());
          book.setPublicationDate(bookDetails.getPublicationDate());
          return bookRepository.save(book);
        });
  }

  @Override
  public void deleteBook(Long id) {
    bookRepository.deleteById(id);
  }

  @Override
  public Optional<String> getBookWithAuthor(Long bookId) {
    return bookRepository.findById(bookId)
        .map(book -> {
          try {
            AuthorDTO author = authorServiceClient.getAuthor(book.getAuthorId()).block();
            return String.format("Book: %s, Author: %s, Publication Date: %s",
                book.getTitle(), author.getName(), book.getPublicationDate());
          } catch (AuthorNotFoundException e) {
            return String.format("Book: %s, Author: Not found, Publication Date: %s",
                book.getTitle(), book.getPublicationDate());
          } catch (Exception e) {
            return String.format("Book: %s, Author: Error fetching author (%s), Publication Date: %s",
                book.getTitle(), e.getMessage(), book.getPublicationDate());
          }
        });
  }
}