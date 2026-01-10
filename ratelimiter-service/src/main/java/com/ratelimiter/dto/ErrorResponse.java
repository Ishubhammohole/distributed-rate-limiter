package com.ratelimiter.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * Standard error response DTO.
 * Provides consistent error format across all endpoints.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {

    private String error;
    private String message;
    private int status;
    private Instant timestamp;
    private String path;
    private List<ValidationError> validationErrors;

    // Default constructor
    public ErrorResponse() {
        this.timestamp = Instant.now();
    }

    // Constructor for simple errors
    public ErrorResponse(String error, String message) {
        this();
        this.error = error;
        this.message = message;
    }

    // Constructor with status
    public ErrorResponse(String error, String message, int status) {
        this(error, message);
        this.status = status;
    }

    // Constructor with path
    public ErrorResponse(String error, String message, int status, String path) {
        this(error, message, status);
        this.path = path;
    }

    // Static factory methods
    public static ErrorResponse badRequest(String message) {
        return new ErrorResponse("BAD_REQUEST", message, 400);
    }

    public static ErrorResponse badRequest(String message, String path) {
        return new ErrorResponse("BAD_REQUEST", message, 400, path);
    }

    public static ErrorResponse internalError(String message) {
        return new ErrorResponse("INTERNAL_ERROR", message, 500);
    }

    public static ErrorResponse serviceUnavailable(String message) {
        return new ErrorResponse("SERVICE_UNAVAILABLE", message, 503);
    }

    public static ErrorResponse tooManyRequests(String message) {
        return new ErrorResponse("TOO_MANY_REQUESTS", message, 429);
    }

    // Getters and setters
    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public List<ValidationError> getValidationErrors() {
        return validationErrors;
    }

    public void setValidationErrors(List<ValidationError> validationErrors) {
        this.validationErrors = validationErrors;
    }

    /**
     * Nested class for validation error details.
     */
    public static class ValidationError {
        private String field;
        private String message;
        private Object rejectedValue;

        public ValidationError() {}

        public ValidationError(String field, String message) {
            this.field = field;
            this.message = message;
        }

        public ValidationError(String field, String message, Object rejectedValue) {
            this.field = field;
            this.message = message;
            this.rejectedValue = rejectedValue;
        }

        // Getters and setters
        public String getField() {
            return field;
        }

        public void setField(String field) {
            this.field = field;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public Object getRejectedValue() {
            return rejectedValue;
        }

        public void setRejectedValue(Object rejectedValue) {
            this.rejectedValue = rejectedValue;
        }
    }
}