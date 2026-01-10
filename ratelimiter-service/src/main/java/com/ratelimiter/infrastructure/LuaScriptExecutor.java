package com.ratelimiter.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Executes Lua scripts on Redis with basic caching for optimal performance.
 * 
 * Simplified implementation for Phase 1.2 foundation layer.
 * Uses Spring's RedisScript abstraction for reliable script execution.
 */
@Component
public class LuaScriptExecutor {
    
    private static final Logger logger = LoggerFactory.getLogger(LuaScriptExecutor.class);
    
    private final RedisTemplate<String, String> redisTemplate;
    private final ConcurrentHashMap<String, DefaultRedisScript<Long>> longScriptCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DefaultRedisScript<String>> stringScriptCache = new ConcurrentHashMap<>();
    
    public LuaScriptExecutor(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }
    
    /**
     * Execute a Lua script that returns a Long result.
     * 
     * @param script the Lua script content
     * @param keys the Redis keys to pass to the script
     * @param args the arguments to pass to the script
     * @return the script execution result as Long
     */
    public Long executeLong(String script, List<String> keys, Object... args) {
        try {
            DefaultRedisScript<Long> redisScript = longScriptCache.computeIfAbsent(script, s -> {
                DefaultRedisScript<Long> rs = new DefaultRedisScript<>();
                rs.setScriptText(s);
                rs.setResultType(Long.class);
                return rs;
            });
            
            // Convert all arguments to strings for Redis serialization
            String[] stringArgs = new String[args.length];
            for (int i = 0; i < args.length; i++) {
                stringArgs[i] = String.valueOf(args[i]);
            }
            
            return redisTemplate.execute(redisScript, keys, stringArgs);
        } catch (Exception e) {
            logger.error("Failed to execute Lua script: {}", e.getMessage());
            throw new RuntimeException("Lua script execution failed", e);
        }
    }
    
    /**
     * Execute a Lua script that returns a String result.
     * 
     * @param script the Lua script content
     * @param keys the Redis keys to pass to the script
     * @param args the arguments to pass to the script
     * @return the script execution result as String
     */
    public String executeString(String script, List<String> keys, Object... args) {
        try {
            DefaultRedisScript<String> redisScript = stringScriptCache.computeIfAbsent(script, s -> {
                DefaultRedisScript<String> rs = new DefaultRedisScript<>();
                rs.setScriptText(s);
                rs.setResultType(String.class);
                return rs;
            });
            
            // Convert all arguments to strings for Redis serialization
            String[] stringArgs = new String[args.length];
            for (int i = 0; i < args.length; i++) {
                stringArgs[i] = String.valueOf(args[i]);
            }
            
            return redisTemplate.execute(redisScript, keys, stringArgs);
        } catch (Exception e) {
            logger.error("Failed to execute Lua script: {}", e.getMessage());
            throw new RuntimeException("Lua script execution failed", e);
        }
    }
    
    /**
     * Clear the local script cache.
     */
    public void clearCache() {
        longScriptCache.clear();
        stringScriptCache.clear();
        logger.debug("Cleared script cache");
    }
    
    /**
     * Execute a Lua script that returns a List result.
     * 
     * @param script the Lua script content
     * @param keys the Redis keys to pass to the script
     * @param args the arguments to pass to the script
     * @return the script execution result as List
     */
    @SuppressWarnings("unchecked")
    public List<Object> executeList(String script, List<String> keys, Object... args) {
        try {
            DefaultRedisScript<List> redisScript = new DefaultRedisScript<>();
            redisScript.setScriptText(script);
            redisScript.setResultType(List.class);
            
            // Convert all arguments to strings for Redis serialization
            String[] stringArgs = new String[args.length];
            for (int i = 0; i < args.length; i++) {
                stringArgs[i] = String.valueOf(args[i]);
            }
            
            return redisTemplate.execute(redisScript, keys, stringArgs);
        } catch (Exception e) {
            logger.error("Failed to execute Lua script: {}", e.getMessage());
            throw new RuntimeException("Lua script execution failed", e);
        }
    }
    
    /**
     * Get the number of cached scripts.
     * 
     * @return cache size
     */
    public int getCacheSize() {
        return longScriptCache.size() + stringScriptCache.size();
    }
}