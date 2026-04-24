package com.ratelimiter.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * Redis configuration for rate limiting infrastructure.
 *
 * Configures Lettuce for a high-concurrency rate-limiter workload.
 *
 * The service issues one Redis Lua call per request, so a small connection pool
 * performs better than a single shared connection once sustained concurrency
 * rises into the thousands. Borrow-time validation stays disabled so pooled
 * checkout does not add extra round-trips on the hot path.
 */
@Configuration
public class RedisConfig {

    private final RedisProperties redisProperties;

    public RedisConfig(RedisProperties redisProperties) {
        this.redisProperties = redisProperties;
    }

    @Bean
    public LettuceConnectionFactory redisConnectionFactory() {
        // Configure Redis standalone connection
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration();
        config.setHostName(redisProperties.getHost());
        config.setPort(redisProperties.getPort());
        if (redisProperties.getPassword() != null) {
            config.setPassword(redisProperties.getPassword());
        }
        if (redisProperties.getDatabase() != 0) {
            config.setDatabase(redisProperties.getDatabase());
        }

        // Configure Lettuce client options for rate limiting workload
        SocketOptions socketOptions = SocketOptions.builder()
                .connectTimeout(Duration.ofMillis(2000))
                .keepAlive(true)
                .build();

        ClientOptions clientOptions = ClientOptions.builder()
                .socketOptions(socketOptions)
                .autoReconnect(true)
                .build();

        RedisProperties.Pool pool = redisProperties.getLettuce().getPool();
        GenericObjectPoolConfig<?> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(pool.getMaxActive());
        poolConfig.setMaxIdle(pool.getMaxIdle());
        poolConfig.setMinIdle(pool.getMinIdle());
        poolConfig.setMaxWait(pool.getMaxWait());
        poolConfig.setBlockWhenExhausted(true);
        poolConfig.setTestOnBorrow(false);
        poolConfig.setTestWhileIdle(true);
        poolConfig.setTimeBetweenEvictionRuns(Duration.ofSeconds(30));

        LettuceClientConfiguration clientConfig = LettucePoolingClientConfiguration.builder()
                .poolConfig(poolConfig)
                .clientOptions(clientOptions)
                .commandTimeout(redisProperties.getTimeout())
                .shutdownTimeout(Duration.ZERO)
                .build();

        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(config, clientConfig);
        // Disable the shared native connection so synchronous RedisTemplate calls can
        // borrow from the pool under load instead of queueing behind one socket.
        connectionFactory.setShareNativeConnection(false);
        return connectionFactory;
    }

    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        
        // Use String serializers for keys and values (rate limiting uses string operations)
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setValueSerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setHashValueSerializer(stringSerializer);
        
        template.afterPropertiesSet();
        return template;
    }
}
