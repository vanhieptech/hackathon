# API Documentation

## Exposed APIs

### getAllBooks

- **Service Name:** vh-book-store
- **HTTP Method:** GET
- **API Endpoint:** http://vh-book-store:8089/api/books
- **Description:** getAllBooks
- **Version:** 1.0
- **Service Dependencies:** BookService
- **Return Type:** java.util.List
- **Parameters:**

### getBookById

- **Service Name:** vh-book-store
- **HTTP Method:** GET
- **API Endpoint:** http://vh-book-store:8089/api/books/{id}
- **Description:** getBookById
- **Version:** 1.0
- **Service Dependencies:** BookService
- **Return Type:** org.springframework.http.ResponseEntity<java.lang.Long>
- **Parameters:**
  - java.lang.Long id (PathVariable)

### createBook

- **Service Name:** vh-book-store
- **HTTP Method:** POST
- **API Endpoint:** http://vh-book-store:8089/api/books
- **Description:** createBook
- **Version:** 1.0
- **Service Dependencies:** BookService
- **Return Type:** com.example.model.Book
- **Parameters:**
  - com.example.model.Book book (RequestBody)

### updateBook

- **Service Name:** vh-book-store
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

- **Service Name:** vh-book-store
- **HTTP Method:** DELETE
- **API Endpoint:** http://vh-book-store:8089/api/books/{id}
- **Description:** deleteBook
- **Version:** 1.0
- **Service Dependencies:** BookService
- **Return Type:** org.springframework.http.ResponseEntity<java.lang.Long>
- **Parameters:**
  - java.lang.Long id (PathVariable)

### getBookWithAuthor

- **Service Name:** vh-book-store
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
- **URL:** Unknown
- **HTTP Method:** GET
- **Description:** Method: getAuthor
- **Parameters:**
- **Response Type:** com.example.dto.AuthorDTO

