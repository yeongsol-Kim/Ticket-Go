# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Ticketgo is a ticketing service built with Spring Boot 3.5.9 and Java 21. The project uses Gradle 8.14.3 as the build system and follows standard Spring Boot conventions.

## Build and Development Commands

### Running the Application
```bash
./gradlew bootRun
```

### Building
```bash
./gradlew build          # Build and run all tests
./gradlew assemble       # Build without running tests
./gradlew clean build    # Clean build
```

### Testing
```bash
./gradlew test                    # Run all tests
./gradlew test --tests ClassName  # Run specific test class
./gradlew test --tests ClassName.methodName  # Run specific test method
```

### Other Useful Commands
```bash
./gradlew bootJar              # Create executable JAR
./gradlew bootBuildImage       # Build OCI/Docker image
./gradlew dependencies         # View dependency tree
./gradlew tasks                # List all available tasks
```

## Technology Stack

- **Java**: 21 (toolchain-managed)
- **Spring Boot**: 3.5.9
- **Core Dependencies**:
  - Spring Web (REST APIs)
  - Spring Data JPA (database persistence)
  - Spring Security (authentication/authorization)
  - Spring Validation (input validation)
  - MySQL Connector (database driver)
  - Lombok (boilerplate reduction)
- **Build Tool**: Gradle 8.14.3

## Project Structure

```
src/
├── main/
│   ├── java/com/yeongsol/ticketgo/
│   │   └── TicketgoApplication.java    # Main application entry point
│   └── resources/
│       └── application.properties       # Configuration
└── test/
    └── java/com/yeongsol/ticketgo/
        └── TicketgoApplicationTests.java
```

The project follows standard Spring Boot package-by-layer or package-by-feature structure. The main package is `com.yeongsol.ticketgo`.

## Architecture Notes

- **Spring Boot Auto-configuration**: The application uses `@SpringBootApplication` which enables component scanning, auto-configuration, and configuration properties.
- **Database**: Configured to use MySQL. Database connection details should be in `application.properties`.
- **Security**: Spring Security is included, which means endpoints are secured by default unless explicitly configured otherwise.
- **JPA/Hibernate**: Used for ORM and database operations.
- **Lombok**: Annotations like `@Data`, `@Builder`, `@NoArgsConstructor`, `@AllArgsConstructor` are available for reducing boilerplate.

## Development Workflow

1. The project uses Spring Boot DevTools for hot reloading during development.
2. Tests use JUnit 5 (Jupiter) with Spring Boot Test support.
3. Database migrations/schema management strategy is not yet defined in the current codebase.
4. The application runs on the default Spring Boot port (8080) unless configured otherwise.
