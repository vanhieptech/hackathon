# API Documentation

## Exposed APIs

### getAllBooks

- **Service Name:** book-service-0.0
- **HTTP Method:** GET
- **API Endpoint:** http://vh-book-store:8089/api/books
- **Description:** getAllBooks
- **Version:** 1.0
- **Service Dependencies:** BookService
- **Return Type:** java.util.List
- **Parameters:**

### getBookById

- **Service Name:** book-service-0.0
- **HTTP Method:** GET
- **API Endpoint:** http://vh-book-store:8089/api/books/{id}
- **Description:** getBookById
- **Version:** 1.0
- **Service Dependencies:** BookService
- **Return Type:** org.springframework.http.ResponseEntity<java.lang.Long>
- **Parameters:**
  - java.lang.Long id (PathVariable)

### createBook

- **Service Name:** book-service-0.0
- **HTTP Method:** POST
- **API Endpoint:** http://vh-book-store:8089/api/books
- **Description:** createBook
- **Version:** 1.0
- **Service Dependencies:** BookService
- **Return Type:** com.example.model.Book
- **Parameters:**
  - com.example.model.Book book (RequestBody)

### updateBook

- **Service Name:** book-service-0.0
- **HTTP Method:** PUT
- **API Endpoint:** http://vh-book-store:8089/api/books/{id}
- **Description:** updateBook
- **Version:** 1.0
- **Service Dependencies:** BookService
- **Return Type:** org.springframework.http.ResponseEntity<java.lang.Long>
- **Parameters:**
  - java.lang.Long id (PathVariable)
  - com.example.model.Book bookDetails (RequestBody)

### deleteBook

- **Service Name:** book-service-0.0
- **HTTP Method:** DELETE
- **API Endpoint:** http://vh-book-store:8089/api/books/{id}
- **Description:** deleteBook
- **Version:** 1.0
- **Service Dependencies:** BookService
- **Return Type:** org.springframework.http.ResponseEntity<java.lang.Long>
- **Parameters:**
  - java.lang.Long id (PathVariable)

### getBookWithAuthor

- **Service Name:** book-service-0.0
- **HTTP Method:** GET
- **API Endpoint:** http://vh-book-store:8089/api/books/{id}/with-author
- **Description:** getBookWithAuthor
- **Version:** 1.0
- **Service Dependencies:** BookService
- **Return Type:** org.springframework.http.ResponseEntity<java.lang.Long>
- **Parameters:**
  - java.lang.Long id (PathVariable)

## External API Calls

### getAuthor

- **Service Name:** AuthorService
- **URL:** http://localhost:8081/api/authors/{id}
- **HTTP Method:** GET
- **Description:** Method: getAuthor
- **Parameters:**
  - id
- **Response Type:** com.example.dto.AuthorDTO

