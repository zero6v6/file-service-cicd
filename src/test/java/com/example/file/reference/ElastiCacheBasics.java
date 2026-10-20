package com.example.file.reference;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * [참고용 · 실행하지 않음] ElastiCache(Valkey) · Redis 를 Spring 으로 다루는 기초 문법.
 *
 * 테스트가 아님 — @Test 가 없어 gradlew test 가 실행하지 않고, 이미지에도 들어가지 않음(src/test).
 *
 * 먼저 알아 둘 것 : ElastiCache 에는 AWS SDK 를 쓰지 않음.
 *   ElastiCache 는 AWS 가 운영해 주는 Redis 호환 서버 → 앱은 Redis 프로토콜(TCP 6379)로 바로 붙음.
 *   Spring Boot 과정에서 로컬 Redis 에 붙던 코드(Lettuce · StringRedisTemplate · @Cacheable)가 그대로이고,
 *   spring.data.redis.host 만 ElastiCache 기본 엔드포인트로 바뀜. 그래서 이 파일에는 software.amazon.awssdk import 가 없음.
 *   (AWS SDK 의 ElastiCacheClient 는 노드를 만들고 · 지우고 · 재부팅하는 관리 API — 캐시 값 읽기 · 쓰기는 없음)
 *
 * 읽는 순서 : 1 Create → 2 Read → 3 Update → 4 Delete (StringRedisTemplate · 명령을 직접)
 *            → 5 오늘 코드 (같은 일을 표기 @Cacheable · @CacheEvict 가 대신 함)
 *
 *   하고 싶은 일     StringRedisTemplate                       Redis 명령        redis-cli 로 확인
 *   넣기(+유효 시간)  opsForValue().set(k, v, Duration)          SET k v PX ms     GET k · TTL k
 *   읽기             opsForValue().get(k)                       GET k
 *   고치기           set 다시(덮어쓰기) · increment(k) · expire   SET · INCR · PEXPIRE
 *   지우기           delete(k)                                  DEL k             GET k → (nil)
 *
 * StringRedisTemplate 은 Spring Boot 가 spring.data.redis.* 로 자동으로 만들어 줌 → 쓰는 쪽은 주입만 받음.
 */
final class ElastiCacheBasics {

    private ElastiCacheBasics() {
    }

    // =====================================================================================
    // 1 Create — SET (유효 시간과 함께)
    // =====================================================================================

    /**
     * 기능      키 하나에 문자열 값 넣기 · 유효 시간 30초
     * 활용      opsForValue() = 문자열 값 다루기 (opsForHash · opsForList · opsForSet · opsForZSet 도 있음)
     * 동작결과  SET memo::1 "첫 메모" PX 30000 · 같은 키가 있으면 덮어씀
     *           유효 시간 없이 넣으면 메모리가 찰 때까지 남음 → 캐시 용도라면 거의 항상 유효 시간을 줌
     */
    static void create(StringRedisTemplate redis) {
        redis.opsForValue().set("memo::1", "첫 메모", Duration.ofSeconds(30));
    }

    /**
     * 기능      없을 때만 넣기 (DynamoDB 의 attribute_not_exists 와 같은 생각)
     * 활용      setIfAbsent → SET … NX PX
     * 동작결과  true = 넣었음 · false = 이미 있어 아무것도 안 함 — 짧은 잠금 · 중복 처리 방지에 씀
     */
    static boolean createIfAbsent(StringRedisTemplate redis) {
        Boolean ok = redis.opsForValue().setIfAbsent("lock::upload-42", "worker-a", Duration.ofSeconds(10));
        return Boolean.TRUE.equals(ok);
    }

    // =====================================================================================
    // 2 Read — GET · TTL
    // =====================================================================================

    /**
     * 기능      값 읽기 · 남은 유효 시간 보기
     * 활용      get(k) → GET · getExpire(k) → TTL(초)
     * 동작결과  없으면 null (예외가 아님) · getExpire 는 키 없음 -2 · 유효 시간 없음 -1
     */
    static String read(StringRedisTemplate redis) {
        String value = redis.opsForValue().get("memo::1");
        Long secondsLeft = redis.getExpire("memo::1");
        return value + " (남은 " + secondsLeft + "초)";
    }

    // =====================================================================================
    // 3 Update — 다시 SET · INCR · EXPIRE
    // =====================================================================================

    /**
     * 기능      값 바꾸기 · 숫자 더하기 · 유효 시간 늘리기
     * 활용      Redis 에는 "일부만 고치기" 가 없음 → 같은 키에 다시 set (값 전체를 바꿈)
     *           increment(k) → INCR · 서버가 그 자리에서 1 을 더함(원자적) — DynamoDB 의 ADD 와 같은 생각
     *           expire(k, Duration) → 유효 시간 다시 정하기
     * 동작결과  INCR 은 키가 없으면 0 에서 시작 → 1
     *           단, 캐시 노드가 재부팅되면 이 숫자는 사라짐 → 잃으면 안 되는 횟수는 DynamoDB 에 (오늘 STATS 항목)
     */
    static long update(StringRedisTemplate redis) {
        redis.opsForValue().set("memo::1", "고친 메모", Duration.ofSeconds(30));
        redis.expire("memo::1", Duration.ofMinutes(5));
        Long views = redis.opsForValue().increment("views::file-42");
        return views == null ? 0 : views;
    }

    // =====================================================================================
    // 4 Delete — DEL
    // =====================================================================================

    /**
     * 기능      키 지우기
     * 활용      delete(k) → DEL · hasKey(k) → EXISTS
     * 동작결과  true = 지웠음 · false = 원래 없었음 (오류가 아님)
     *           "files::*" 처럼 패턴으로 지우는 KEYS 명령은 운영에서 피함(키 전체를 훑어 서버가 멈출 수 있음)
     */
    static boolean delete(StringRedisTemplate redis) {
        Boolean deleted = redis.delete("memo::1");
        return Boolean.TRUE.equals(deleted);
    }

    // =====================================================================================
    // 5 오늘 코드 — 위의 GET · SET · DEL 을 표기가 대신 부름 (FileService · CacheConfig)
    // =====================================================================================

    /**
     * 표기 없이 캐시 객체를 직접 쓰면 이런 모양 — @Cacheable 이 안에서 하는 일과 같음.
     *   cache.get("list")          → GET files::list
     *   cache.put("list", value)   → SET files::list <JSON> PX 30000   (TTL · JSON 은 CacheConfig 의 RedisCacheConfiguration)
     *   cache.evict("list")        → DEL files::list
     * 키 files::list = 캐시 이름 files + "::" + 키 list
     */
    static String cacheByHand(CacheManager cacheManager) {
        Cache cache = cacheManager.getCache("files");
        if (cache == null) {
            return null;
        }
        String cached = cache.get("list", String.class);
        if (cached != null) {
            return cached;                                     // 적중 — DB 를 부르지 않음
        }
        String fromDb = "DB 에서 읽은 목록";                     // 누락 — 원본(DB)에서 읽고
        cache.put("list", fromDb);                             // 캐시에 넣음
        return fromDb;
    }

    /**
     * 표기 모음 (이 클래스는 빈이 아니므로 실제로 동작하지 않음 — 쓰는 법만)
     *
     * - Cacheable   결과를 캐시 · 있으면 본문을 실행하지 않음
     *   key = "#id"            메서드 인자로 키 만들기 (SpEL) → files::42
     *   sync = true            같은 인스턴스 안에서 동시에 누락되면 한 요청만 DB 로 (캐시 스탬피드 완화 · 인스턴스끼리는 아님)
     *   unless = "#result == null"   결과가 null 이면 캐시에 넣지 않음
     * - CachePut    본문을 늘 실행하고 결과로 캐시를 덮어씀 (쓰기와 함께 캐시를 갱신)
     * - CacheEvict  본문이 성공한 뒤 캐시를 지움 · beforeInvocation = true 면 먼저 지움(오늘 쓰지 않는 이유는 교안 02 절)
     */
    static class AnnotationExamples {

        @Cacheable(value = "files", key = "'list'")
        public String list() {
            return "DB 에서 읽은 목록";
        }

        @Cacheable(value = "file", key = "#id", sync = true)
        public String one(String id) {
            return "DB 에서 읽은 " + id;
        }

        @Cacheable(value = "file", key = "#id", unless = "#result == null")
        public String oneOrNull(String id) {
            return null;
        }

        @CachePut(value = "file", key = "#id")
        public String rename(String id, String newName) {
            return newName;
        }

        @CacheEvict(value = "files", key = "'list'")
        public void upload() {
        }
    }
}
