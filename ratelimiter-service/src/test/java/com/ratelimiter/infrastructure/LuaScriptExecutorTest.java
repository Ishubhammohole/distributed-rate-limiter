package com.ratelimiter.infrastructure;

import org.junit.jupiter.api.BeforeEach;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for LuaScriptExecutor using Testcontainers.
 */
@SpringBootTest
@Testcontainers
class LuaScriptExecutorTest {

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

    private LuaScriptExecutor scriptExecutor;

    @BeforeEach
    void setUp() {
        scriptExecutor = new LuaScriptExecutor(redisTemplate);
        // Clear Redis before each test
        redisTemplate.getConnectionFactory().getConnection().flushAll();
        scriptExecutor.clearCache();
    }

    @Test
    void executeLong_simpleScript_shouldReturnResult() {
        // Given
        String script = "return 42";
        List<String> keys = List.of();

        // When
        Long result = scriptExecutor.executeLong(script, keys);

        // Then
        assertThat(result).isEqualTo(42L);
    }

    @Test
    void executeString_simpleScript_shouldReturnResult() {
        // Given
        String script = "return 'hello'";
        List<String> keys = List.of();

        // When
        String result = scriptExecutor.executeString(script, keys);

        // Then
        assertThat(result).isEqualTo("hello");
    }

    @Test
    void executeLong_scriptWithKeys_shouldAccessRedisKeys() {
        // Given
        String script = "redis.call('SET', KEYS[1], ARGV[1]); return redis.call('GET', KEYS[1])";
        List<String> keys = List.of("test:key");
        String value = "123";

        // When
        Long result = scriptExecutor.executeLong(script, keys, value);

        // Then
        assertThat(result).isEqualTo(123L);
    }

    @Test
    void executeString_scriptWithMultipleArgs_shouldHandleAllArgs() {
        // Given
        String script = "return ARGV[1] .. ':' .. ARGV[2] .. ':' .. ARGV[3]";
        List<String> keys = List.of();

        // When
        String result = scriptExecutor.executeString(script, keys, "a", "b", "c");

        // Then
        assertThat(result).isEqualTo("a:b:c");
    }

    @Test
    void executeLong_sameScriptMultipleTimes_shouldUseCaching() {
        // Given
        String script = "return 100";
        List<String> keys = List.of();

        // When - Execute same script multiple times
        Long result1 = scriptExecutor.executeLong(script, keys);
        Long result2 = scriptExecutor.executeLong(script, keys);
        Long result3 = scriptExecutor.executeLong(script, keys);

        // Then
        assertThat(result1).isEqualTo(100L);
        assertThat(result2).isEqualTo(100L);
        assertThat(result3).isEqualTo(100L);
        
        // Cache should contain the script
        assertThat(scriptExecutor.getCacheSize()).isEqualTo(1);
    }

    @Test
    void clearCache_shouldRemoveAllCachedScripts() {
        // Given
        String script1 = "return 1";
        String script2 = "return 'test'";
        scriptExecutor.executeLong(script1, List.of());
        scriptExecutor.executeString(script2, List.of());
        assertThat(scriptExecutor.getCacheSize()).isEqualTo(2);

        // When
        scriptExecutor.clearCache();

        // Then
        assertThat(scriptExecutor.getCacheSize()).isEqualTo(0);
    }

    @Test
    void executeLong_scriptWithRedisOperations_shouldModifyRedisState() {
        // Given
        String script = """
            local key = KEYS[1]
            local increment = tonumber(ARGV[1])
            local current = redis.call('GET', key)
            if current == false then
                current = 0
            else
                current = tonumber(current)
            end
            local new_value = current + increment
            redis.call('SET', key, new_value)
            return new_value
            """;
        List<String> keys = List.of("counter");

        // When
        Long result1 = scriptExecutor.executeLong(script, keys, "5");
        Long result2 = scriptExecutor.executeLong(script, keys, "3");

        // Then
        assertThat(result1).isEqualTo(5L);
        assertThat(result2).isEqualTo(8L);
        
        // Verify Redis state
        String finalValue = redisTemplate.opsForValue().get("counter");
        assertThat(finalValue).isEqualTo("8");
    }

    @Test
    void executeLong_invalidScript_shouldThrowException() {
        // Given
        String invalidScript = "invalid lua syntax !!!";
        List<String> keys = List.of();

        // When/Then
        assertThatThrownBy(() -> scriptExecutor.executeLong(invalidScript, keys))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Lua script execution failed");
    }
}