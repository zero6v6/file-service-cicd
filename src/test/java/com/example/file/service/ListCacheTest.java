package com.example.file.service;

import com.example.file.storage.StoredFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 목록 캐시(cache-aside) 테스트. local 은 메모리 캐시(simple) — 동작은 prod 의 Redis 와 같음.
 * DB 에 직접 행을 넣으면(캐시를 지우지 않음) 캐시에 있는 옛 목록이 그대로 나오는지를 봄.
 */
@SpringBootTest
@ActiveProfiles("local")
class ListCacheTest {

    @Autowired FileService fileService;
    @Autowired FileRegistry registry;
    @Autowired CacheManager cacheManager;

    @BeforeEach
    void 캐시_비우기() {
        cacheManager.getCache("files").clear();
    }

    @Test
    void 두_번째_조회는_캐시에서_옴() {
        int first = fileService.listCached().items().size();      // DB → 캐시에 넣음

        registry.put(new StoredFile(UUID.randomUUID().toString(), "c.txt", "k/c.txt", 1, "text/plain", Instant.now()));
        assertEquals(first, fileService.listCached().items().size(), "캐시가 옛 목록을 돌려줌 (DB 를 안 봄)");

        cacheManager.getCache("files").evict("list");               // @CacheEvict 가 하는 일
        assertEquals(first + 1, fileService.listCached().items().size(), "지운 뒤에는 DB 에서 새로 읽음");
    }
}
