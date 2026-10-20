package com.example.file.service;

import com.example.file.storage.FileStorage;
import com.example.file.storage.StoredFile;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FileService 테스트. 목록이 DB(H2)에 있으므로 스프링 컨텍스트를 띄우고,
 * 저장소만 가짜(FakeStorage)로 바꿔 끼움 — @Primary 가 LocalFileStorage 보다 우선함.
 */
@SpringBootTest
@ActiveProfiles("local")
@Import(FileServiceTest.FakeStorageConfig.class)
class FileServiceTest {

    /** 규약을 최소로 구현한 가짜 저장소. 프리사인 URL 은 고정 문자열로 돌려줌 */
    static class FakeStorage implements FileStorage {
        final Map<String, byte[]> store = new HashMap<>();

        @Override public String kind() { return "fake"; }

        @Override public String save(MultipartFile file, String key) throws IOException {
            store.put(key, file.getBytes());
            return key;
        }

        @Override public Resource load(String key) { return new ByteArrayResource(store.get(key)); }

        @Override public void delete(String key) { store.remove(key); }

        @Override public Optional<String> downloadUrl(String key, String originalName, Duration ttl) {
            return Optional.of("https://fake/" + key + "?expires=" + ttl.toSeconds());
        }
    }

    @TestConfiguration
    static class FakeStorageConfig {
        @Bean @Primary
        FakeStorage fakeStorage() { return new FakeStorage(); }
    }

    @Autowired FileService service;
    @Autowired FileRegistry registry;
    @Autowired FakeStorage storage;

    private StoredFile uploadHello() throws IOException {
        MockMultipartFile file = new MockMultipartFile("file", "hello.txt", "text/plain", "hello".getBytes());
        return service.upload(file);
    }

    @Test
    void 업로드하면_저장소와_목록에_같이_들어감() throws IOException {
        int before = registry.size();
        StoredFile stored = uploadHello();
        assertTrue(storage.store.containsKey(stored.key()));
        assertEquals(before + 1, registry.size());
        assertEquals("hello.txt", stored.originalName());
    }

    @Test
    void 삭제하면_저장소와_목록에서_같이_빠짐() throws IOException {
        int before = registry.size();
        StoredFile stored = uploadHello();
        service.delete(stored.id());
        assertTrue(!storage.store.containsKey(stored.key()));
        assertEquals(before, registry.size());
    }

    @Test
    void 직접_다운로드_URL_은_저장소가_만든_값을_그대로_돌려줌() throws IOException {
        StoredFile stored = uploadHello();
        Optional<String> url = service.downloadUrl(stored.id());
        assertTrue(url.isPresent());
        assertEquals("https://fake/" + stored.key() + "?expires=300", url.get());
    }
}
