package com.ratelimiter.infrastructure;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for RedisTimeProvider using Testcontainers.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Tag("integration")
class RedisTimeProviderTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private LuaScriptExecutor scriptExecutor;

    private RedisTimeProvider timeProvider;

    @BeforeEach
    void setUp() {
        timeProvider = new RedisTimeProvider(scriptExecutor);
    }

    @Test
    void getCurrentTime_shouldReturnTimeFromRedis() {
        // When
        Instant redisTime = timeProvider.getCurrentTime();
        Instant systemTime = Instant.now();

        // Then
        assertThat(redisTime).isNotNull();
        // Redis time should be close to system time (within 5 seconds)
        assertThat(redisTime).isBetween(
            systemTime.minus(5, ChronoUnit.SECONDS),
            systemTime.plus(5, ChronoUnit.SECONDS)
        );
    }

    @Test
    void getCurrentTime_withFallbackTrue_shouldReturnTimeWhenRedisAvailable() {
        // When
        Instant redisTime = timeProvider.getCurrentTime(true);
        Instant systemTime = Instant.now();

        // Then
        assertThat(redisTime).isNotNull();
        assertThat(redisTime).isBetween(
            systemTime.minus(5, ChronoUnit.SECONDS),
            systemTime.plus(5, ChronoUnit.SECONDS)
        );
    }

    @Test
    void getCurrentTimestamp_shouldReturnUnixTimestamp() {
        // When
        long timestamp = timeProvider.getCurrentTimestamp();
        long systemTimestamp = Instant.now().getEpochSecond();

        // Then
        assertThat(timestamp).isCloseTo(systemTimestamp, org.assertj.core.data.Offset.offset(5L));
    }

    @Test
    void getCurrentTimestampMillis_shouldReturnUnixTimestampInMillis() {
        // When
        long timestampMillis = timeProvider.getCurrentTimestampMillis();
        long systemTimestampMillis = Instant.now().toEpochMilli();

        // Then
        assertThat(timestampMillis).isCloseTo(systemTimestampMillis, 
            org.assertj.core.data.Offset.offset(5000L));
    }

    @Test
    void multipleCallsToGetCurrentTime_shouldReturnIncreasingValues() {
        // When
        Instant time1 = timeProvider.getCurrentTime();
        
        // Small delay to ensure time progression
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        Instant time2 = timeProvider.getCurrentTime();

        // Then
        assertThat(time2).isAfterOrEqualTo(time1);
    }
}