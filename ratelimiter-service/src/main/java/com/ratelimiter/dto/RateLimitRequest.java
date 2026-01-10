package com.ratelimiter.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for rate limit checks.
 * Contains all parameters needed to perform a rate limit decision.
 */
public class RateLimitRequest {

    @NotBlank(message = "Key cannot be blank")
    @Size(max = 256, message = "Key length cannot exceed 256 characters")
    @Pattern(regexp = "^[a-zA-Z0-9:._-]+$", message = "Key contains invalid characters")
    private String key;

    @NotBlank(message = "Algorithm cannot be blank")
    @Pattern(regexp = "^(token_bucket|sliding_window_log|fixed_window|sliding_window_counter)$", 
             message = "Algorithm must be one of: token_bucket, sliding_window_log, fixed_window, sliding_window_counter")
    private String algorithm;

    @NotNull(message = "Limit cannot be null")
    @Min(value = 1, message = "Limit must be at least 1")
    private Long limit;

    @NotBlank(message = "Window cannot be blank")
    @Pattern(regexp = "^\\d+[smhd]$", message = "Window must be in format: number followed by s/m/h/d (e.g., '60s', '5m')")
    private String window;

    @Min(value = 1, message = "Cost must be at least 1")
    private int cost = 1;

    // Default constructor
    public RateLimitRequest() {}

    // Constructor with all fields
    public RateLimitRequest(String key, String algorithm, Long limit, String window, int cost) {
        this.key = key;
        this.algorithm = algorithm;
        this.limit = limit;
        this.window = window;
        this.cost = cost;
    }

    // Getters and setters
    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(String algorithm) {
        this.algorithm = algorithm;
    }

    public Long getLimit() {
        return limit;
    }

    public void setLimit(Long limit) {
        this.limit = limit;
    }

    public String getWindow() {
        return window;
    }

    public void setWindow(String window) {
        this.window = window;
    }

    public int getCost() {
        return cost;
    }

    public void setCost(int cost) {
        this.cost = cost;
    }

    @Override
    public String toString() {
        return "RateLimitRequest{" +
                "key='" + key + '\'' +
                ", algorithm='" + algorithm + '\'' +
                ", limit=" + limit +
                ", window='" + window + '\'' +
                ", cost=" + cost +
                '}';
    }
}