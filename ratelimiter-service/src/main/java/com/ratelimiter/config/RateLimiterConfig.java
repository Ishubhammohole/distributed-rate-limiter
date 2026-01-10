package com.ratelimiter.config;

import com.ratelimiter.domain.RateLimitAlgorithm;
import com.ratelimiter.strategy.RateLimiterStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration for rate limiter strategies and algorithm registry.
 * 
 * Wires strategy implementations to their corresponding algorithms
 * and provides the strategy map to the service layer.
 */
@Configuration
public class RateLimiterConfig {
    
    private static final Logger logger = LoggerFactory.getLogger(RateLimiterConfig.class);
    
    /**
     * Create the strategy registry that maps algorithms to their implementations.
     * 
     * @param strategies list of all available strategy implementations
     * @return map of algorithm to strategy implementation
     */
    @Bean
    public Map<RateLimitAlgorithm, RateLimiterStrategy> rateLimiterStrategies(
            List<RateLimiterStrategy> strategies) {
        
        Map<RateLimitAlgorithm, RateLimiterStrategy> strategyMap = new HashMap<>();
        
        for (RateLimiterStrategy strategy : strategies) {
            RateLimitAlgorithm algorithm = strategy.getAlgorithm();
            
            if (strategyMap.containsKey(algorithm)) {
                logger.warn("Multiple strategies found for algorithm {}, using: {}", 
                    algorithm, strategy.getClass().getSimpleName());
            }
            
            strategyMap.put(algorithm, strategy);
            logger.info("Registered strategy {} for algorithm {}", 
                strategy.getClass().getSimpleName(), algorithm);
        }
        
        logger.info("Initialized rate limiter with {} strategies", strategyMap.size());
        
        // Log which algorithms are missing implementations
        for (RateLimitAlgorithm algorithm : RateLimitAlgorithm.values()) {
            if (!strategyMap.containsKey(algorithm)) {
                logger.info("No strategy implementation found for algorithm {} (will use mock behavior)", 
                    algorithm);
            }
        }
        
        return strategyMap;
    }
}