# API Documentation

## Exposed APIs

### com/example/controller/BookController.<init>

- **HTTP Method:** null
- **Path:** /api/books
- **Parameters:**
  - com.example.service.BookService
- **Return Type:** void

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

### com/example/service/impl/BookServiceImpl.<init>

- **HTTP Method:** null
- **Path:** null
- **Parameters:**
  - com.example.repository.BookRepository
  - com.example.service.AuthorServiceClient
- **Return Type:** void

### com/example/service/impl/BookServiceImpl.getAllBooks

- **HTTP Method:** null
- **Path:** null
- **Parameters:**
- **Return Type:** java.util.List

### com/example/service/impl/BookServiceImpl.getBookById

- **HTTP Method:** null
- **Path:** null
- **Parameters:**
  - java.lang.Long
- **Return Type:** java.util.Optional

### com/example/service/impl/BookServiceImpl.createBook

- **HTTP Method:** null
- **Path:** null
- **Parameters:**
  - com.example.model.Book
- **Return Type:** com.example.model.Book

### com/example/service/impl/BookServiceImpl.updateBook

- **HTTP Method:** null
- **Path:** null
- **Parameters:**
  - java.lang.Long
  - com.example.model.Book
- **Return Type:** java.util.Optional

### com/example/service/impl/BookServiceImpl.deleteBook

- **HTTP Method:** null
- **Path:** null
- **Parameters:**
  - java.lang.Long
- **Return Type:** void

### com/example/service/impl/BookServiceImpl.getBookWithAuthor

- **HTTP Method:** null
- **Path:** null
- **Parameters:**
  - java.lang.Long
- **Return Type:** java.util.Optional

### com/example/service/impl/BookServiceImpl.lambda$getBookWithAuthor$1

- **HTTP Method:** null
- **Path:** null
- **Parameters:**
  - com.example.model.Book
- **Return Type:** java.lang.String

### com/example/service/impl/BookServiceImpl.lambda$updateBook$0

- **HTTP Method:** null
- **Path:** null
- **Parameters:**
  - com.example.model.Book
  - com.example.model.Book
- **Return Type:** com.example.model.Book

### com/example/service/AuthorServiceClient.<init>

- **HTTP Method:** null
- **Path:** null
- **Parameters:**
  - org.springframework.web.reactive.function.client.WebClient$Builder
- **Return Type:** void

### com/example/service/AuthorServiceClient.getAuthor

- **HTTP Method:** null
- **Path:** null
- **Parameters:**
  - java.lang.Long
- **Return Type:** reactor.core.publisher.Mono

### com/example/service/AuthorServiceClient.lambda$getAuthor$1

- **HTTP Method:** null
- **Path:** null
- **Parameters:**
  - org.springframework.web.reactive.function.client.ClientResponse
- **Return Type:** reactor.core.publisher.Mono

### com/example/service/AuthorServiceClient.lambda$getAuthor$0

- **HTTP Method:** null
- **Path:** null
- **Parameters:**
  - java.lang.Long
  - org.springframework.web.reactive.function.client.ClientResponse
- **Return Type:** reactor.core.publisher.Mono

## External API Calls

