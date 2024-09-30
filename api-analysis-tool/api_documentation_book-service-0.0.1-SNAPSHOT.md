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
  - java.lang.Long java.lang.Long (PathVariable)

### createBook

- **Service Name:** vh-book-store
- **HTTP Method:** POST
- **API Endpoint:** http://vh-book-store:8089/api/books
- **Description:** createBook
- **Version:** 1.0
- **Service Dependencies:** BookService
- **Return Type:** com.example.model.Book
- **Parameters:**
  - com.example.model.Book com.example.model.Book (RequestBody)

### updateBook

- **Service Name:** vh-book-store
- **HTTP Method:** PUT
- **API Endpoint:** http://vh-book-store:8089/api/books/{id}
- **Description:** updateBook
- **Version:** 1.0
- **Service Dependencies:** BookService
- **Return Type:** org.springframework.http.ResponseEntity<java.lang.Long>
- **Parameters:**
  - java.lang.Long java.lang.Long (PathVariable)
  - com.example.model.Book com.example.model.Book (RequestBody)

### deleteBook

- **Service Name:** vh-book-store
- **HTTP Method:** DELETE
- **API Endpoint:** http://vh-book-store:8089/api/books/{id}
- **Description:** deleteBook
- **Version:** 1.0
- **Service Dependencies:** BookService
- **Return Type:** org.springframework.http.ResponseEntity<java.lang.Long>
- **Parameters:**
  - java.lang.Long java.lang.Long (PathVariable)

### getBookWithAuthor

- **Service Name:** vh-book-store
- **HTTP Method:** GET
- **API Endpoint:** http://vh-book-store:8089/api/books/{id}/with-author
- **Description:** getBookWithAuthor
- **Version:** 1.0
- **Service Dependencies:** BookService
- **Return Type:** org.springframework.http.ResponseEntity<java.lang.Long>
- **Parameters:**
  - java.lang.Long java.lang.Long (PathVariable)

### getAuthorById

- **Service Name:** vh-book-store
- **HTTP Method:** GET
- **API Endpoint:** http://vh-book-store:8089/authors/{id}
- **Description:** getAuthorById
- **Version:** 1.0
- **Service Dependencies:** 
- **Return Type:** reactor.core.publisher.Mono<java.lang.Long>
- **Parameters:**
  - java.lang.Long id (PathVariable)
  - org.springframework.web.server.ServerWebExchange exchange

## External API Calls

### getAuthor

- **Service Name:** vh-author
- **URL:** http://vh-author:8081/api/authors/{id}
- **HTTP Method:** GET
- **Description:** Method: getAuthor
- **Parameters:**
  - id
- **Response Type:** reactor.core.publisher.Mono

