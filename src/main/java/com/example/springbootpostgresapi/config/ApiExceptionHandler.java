package com.example.springbootpostgresapi.config;

import java.time.format.DateTimeParseException;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// Turns bad client input that slips past validation into 400 instead of 500.
// Messages are fixed text so DB and parser details never reach the client.
@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(DateTimeParseException.class)
    public ResponseEntity<Map<String, String>> badDate(DateTimeParseException e) {
        return badRequest("Invalid date, expected yyyy-MM-dd");
    }

    @ExceptionHandler(NumberFormatException.class)
    public ResponseEntity<Map<String, String>> badNumber(NumberFormatException e) {
        return badRequest("Invalid number");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> badData(DataIntegrityViolationException e) {
        log.warn("Rejected write: {}", e.getMostSpecificCause().getMessage());
        return badRequest("Value too long or not allowed");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badArgument(IllegalArgumentException e) {
        log.warn("Rejected request: {}", e.getMessage());
        return badRequest("Invalid request");
    }

    private ResponseEntity<Map<String, String>> badRequest(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", message));
    }
}
