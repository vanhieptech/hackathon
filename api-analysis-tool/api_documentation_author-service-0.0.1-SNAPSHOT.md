# API Documentation

## Exposed APIs

### getAllAuthors

- **Service Name:** author-service-0.0
- **HTTP Method:** GET
- **API Endpoint:** http://:8081/api/authors
- **Description:** getAllAuthors
- **Version:** 1.0
- **Service Dependencies:** AuthorService
- **Return Type:** java.util.List
- **Parameters:**

### getAuthorById

- **Service Name:** author-service-0.0
- **HTTP Method:** GET
- **API Endpoint:** http://:8081/api/authors/{id}
- **Description:** getAuthorById
- **Version:** 1.0
- **Service Dependencies:** AuthorService
- **Return Type:** org.springframework.http.ResponseEntity<java.lang.Long>
- **Parameters:**
  - java.lang.Long id (PathVariable)

### createAuthor

- **Service Name:** author-service-0.0
- **HTTP Method:** POST
- **API Endpoint:** http://:8081/api/authors
- **Description:** createAuthor
- **Version:** 1.0
- **Service Dependencies:** AuthorService
- **Return Type:** com.example.authorservice.model.Author
- **Parameters:**
  - com.example.authorservice.model.Author author (RequestBody)

### updateAuthor

- **Service Name:** author-service-0.0
- **HTTP Method:** PUT
- **API Endpoint:** http://:8081/api/authors/{id}
- **Description:** updateAuthor
- **Version:** 1.0
- **Service Dependencies:** AuthorService
- **Return Type:** org.springframework.http.ResponseEntity<java.lang.Long>
- **Parameters:**
  - java.lang.Long id (PathVariable)
  - com.example.authorservice.model.Author authorDetails (RequestBody)

### deleteAuthor

- **Service Name:** author-service-0.0
- **HTTP Method:** DELETE
- **API Endpoint:** http://:8081/api/authors/{id}
- **Description:** deleteAuthor
- **Version:** 1.0
- **Service Dependencies:** AuthorService
- **Return Type:** org.springframework.http.ResponseEntity<java.lang.Long>
- **Parameters:**
  - java.lang.Long id (PathVariable)

## External API Calls

