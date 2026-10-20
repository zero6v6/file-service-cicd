package com.example.file.service;

import com.example.file.storage.StoredFile;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FileRegistry(JPA) 테스트. local 프로파일의 H2 메모리 DB 를 씀 — MySQL 설치 불필요.
 * 같은 메서드 이름으로 Map 이 DB 로 바뀌었음을 확인함.
 */
@SpringBootTest
@ActiveProfiles("local")
class FileRegistryTest {

    @Autowired FileRegistry registry;

    private static StoredFile sample(String name) {
        return new StoredFile(UUID.randomUUID().toString(), name, "k/" + name, 5, "text/plain", Instant.now());
    }

    @Test
    void 저장하면_목록과_개수에_반영됨() {
        int before = registry.size();
        StoredFile f = sample("a.txt");
        registry.put(f);
        assertEquals(before + 1, registry.size());
        assertTrue(registry.find(f.id()).isPresent());
        assertEquals("a.txt", registry.find(f.id()).get().originalName());
    }

    @Test
    void 지우면_목록에서_빠짐() {
        StoredFile f = sample("b.txt");
        registry.put(f);
        registry.remove(f.id());
        assertTrue(registry.find(f.id()).isEmpty());
    }

    // TODO 4 · FileRegistry.takePending 을 작성한 뒤 아래 주석을 풀면 통과해야 함
    @Test
    void 대기_행은_한_번만_꺼내짐() {
        StoredFile f = sample("pending.txt");
        registry.putPending(f);
        assertTrue(registry.find(f.id()).isEmpty());               // 대기 행은 정식 목록에 없음
        assertTrue(registry.takePending(f.id()).isPresent());       // 첫 번째는 꺼냄
        assertTrue(registry.takePending(f.id()).isEmpty());         // 두 번째는 비어 있음
    }
}
