package com.onlineshopping.service;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class RedisService {
    private final RedisTemplate<String, Object> redisTemplate;

    public RedisService(RedisTemplate<String, Object> redisTemplate){
        this.redisTemplate = redisTemplate;
    }

    // 获取锁
    public boolean tryLock(String key, long expireSeconds) {
        // SET key "locked" NX EX expireSeconds
        return Boolean.TRUE.equals(redisTemplate.opsForValue()
                .setIfAbsent(key, "locked", expireSeconds, TimeUnit.SECONDS));
    }

    // 释放锁
    public void unlock(String key) {
        // DELETE key
        this.redisTemplate.delete(key);
    }

    public boolean isRateLimited(String key, long maxRequests, long windowSeconds) {
        // 1. INCR key（原子操作，返回递增后的值）
        Long count = redisTemplate.opsForValue().increment(key);
        // 2. 如果值 == 1（第一次），设TTL
        if (count != null && count == 1) redisTemplate.expire(key, windowSeconds, TimeUnit.SECONDS);
        // 3. 如果值 > maxRequests → return true（被限流）
        return count != null && count > maxRequests;
        // 4. 否则return false
    }
}
