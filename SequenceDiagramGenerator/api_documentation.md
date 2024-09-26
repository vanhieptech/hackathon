# API Documentation

## Exposed APIs

### com/example/controller/BookController.getAllBooks

- **HTTP Method:** GET
- **Path:** /api/books
- **Parameters:**
- **Return Type:** java.util.List

### com/example/controller/BookController.getBookById

- **HTTP Method:** GET
- **Path:** /api/books/{id}
- **Parameters:**
  - java.lang.Long
- **Return Type:** org.springframework.http.ResponseEntity

### com/example/controller/BookController.createBook

- **HTTP Method:** POST
- **Path:** /api/books
- **Parameters:**
  - com.example.model.Book
- **Return Type:** com.example.model.Book

### com/example/controller/BookController.updateBook

- **HTTP Method:** PUT
- **Path:** /api/books/{id}
- **Parameters:**
  - java.lang.Long
  - com.example.model.Book
- **Return Type:** org.springframework.http.ResponseEntity

### com/example/controller/BookController.deleteBook

- **HTTP Method:** DELETE
- **Path:** /api/books/{id}
- **Parameters:**
  - java.lang.Long
- **Return Type:** org.springframework.http.ResponseEntity

### com/example/controller/BookController.getBookWithAuthor

- **HTTP Method:** GET
- **Path:** /api/books/{id}/with-author
- **Parameters:**
  - java.lang.Long
- **Return Type:** org.springframework.http.ResponseEntity

## External API Calls

