package com.example.authorservice.service;

import com.example.authorservice.model.Author;
import com.example.authorservice.repository.AuthorRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AuthorService {
    private final AuthorRepository authorRepository;

    public AuthorService(AuthorRepository authorRepository) {
        this.authorRepository = authorRepository;
    }

    public List<Author> getAllAuthors() {
        return authorRepository.findAll();
    }

    public Author getAuthorById(Long id) {
        return authorRepository.findById(id).orElse(null);
    }

    public Author createAuthor(Author author) {
        return authorRepository.save(author);
    }

    public Author updateAuthor(Long id, Author authorDetails) {
        Author author = authorRepository.findById(id).orElse(null);
        if (author != null) {
            author.setName(authorDetails.getName());
            author.setBiography(authorDetails.getBiography());
            return authorRepository.save(author);
        }
        return null;
    }

    public void deleteAuthor(Long id) {
        authorRepository.deleteById(id);
    }
}