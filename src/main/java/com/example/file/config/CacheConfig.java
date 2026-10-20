package com.example.file.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.time.Duration;

/**
 * 목록 캐시 설정.
 *
 *   local → spring.cache.type=simple  메모리 캐시 · Redis 불필요 · 인스턴스마다 따로
 *   prod  → spring.cache.type=redis   ElastiCache(Valkey) · 모든 인스턴스가 같은 캐시를 봄
 *
 * 캐시는 "선택 저장소" — 원본(RDS)이 따로 있고, 없어져도 느려질 뿐 답은 같음.
 * 그래서 두 가지를 지킴.
 *   1. TTL 30초  목록이 바뀌면 @CacheEvict 로 바로 지우지만, 지우기가 실패해도 30초면 사라짐
 *   2. 오류 무시  캐시가 죽으면 그 오류를 로그만 남기고 DB 로 감(errorHandler) — 500 을 내지 않음
 */
@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    /** Redis 캐시 모양 — 유효 시간과 저장 형식. spring.cache.type=redis 일 때만 쓰임(local 의 simple 에서는 무시) */
    @Bean
    public RedisCacheConfiguration redisCacheConfiguration() {
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofSeconds(30))
                // 값을 JSON 으로 저장. redis-cli GET 으로 사람이 읽을 수 있음
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(RedisSerializer.json()));
    }

    /**
     * 캐시 오류는 "캐시 없음" 으로 취급함.
     * 읽기가 실패하면 메서드가 그냥 실행되어 DB 에서 읽고, 쓰기 · 지우기 실패는 무시함.
     */
    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
                log.warn("캐시 읽기 실패 (DB 로 감) : {}", e.getMessage());
            }

            @Override
            public void handleCachePutError(RuntimeException e, Cache cache, Object key, Object value) {
                log.warn("캐시 쓰기 실패 (무시) : {}", e.getMessage());
            }

            @Override
            public void handleCacheEvictError(RuntimeException e, Cache cache, Object key) {
                log.warn("캐시 지우기 실패 (30초 뒤 만료) : {}", e.getMessage());
            }

            @Override
            public void handleCacheClearError(RuntimeException e, Cache cache) {
                log.warn("캐시 비우기 실패 (30초 뒤 만료) : {}", e.getMessage());
            }
        };
    }
}
