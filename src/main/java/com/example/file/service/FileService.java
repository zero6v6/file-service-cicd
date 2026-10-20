package com.example.file.service;

import com.example.file.common.FileValidator;
import com.example.file.common.NotFoundException;
import com.example.file.storage.FileStorage;
import com.example.file.storage.StoredFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 파일 업무 흐름. 검증 → 저장 → 목록 등록.
 *
 * 저장소가 디스크인지 S3 인지는 FileStorage 뒤에 숨어 있음.
 */
@Service
public class FileService {

    private static final Logger log = LoggerFactory.getLogger(FileService.class);

    private final FileStorage storage;
    private final FileRegistry registry;
    private final FileValidator validator;
    private final Duration urlTtl;

    public FileService(FileStorage storage, FileRegistry registry, FileValidator validator,
                       @Value("${app.presign.ttl-seconds:300}") long urlTtlSeconds) {
        this.storage = storage;
        this.registry = registry;
        this.validator = validator;
        this.urlTtl = Duration.ofSeconds(urlTtlSeconds);
    }

    public String storageKind() {
        return storage.kind();
    }

    /** 목록이 바뀌므로 캐시를 지움 (메서드가 성공한 뒤에 지움 — 그 사이 읽기가 옛 목록을 다시 채우지 않게) */
    @CacheEvict(value = "files", key = "'list'")
    public StoredFile upload(MultipartFile file) throws IOException {
        validator.validate(file);

        String id = UUID.randomUUID().toString();
        // 저장 이름 = id_원본명. 같은 이름을 두 번 올려도 덮어쓰지 않음
        String key = storage.save(file, storage.keyFor(id + "_" + file.getOriginalFilename()));

        StoredFile stored = new StoredFile(
                id, file.getOriginalFilename(), key,
                file.getSize(), file.getContentType(), Instant.now());
        registry.put(stored);
        log.info("업로드 : {} → {} [{}]", stored.originalName(), key, storage.kind());
        return stored;
    }

    /**
     * 직접 업로드 1단계. 이름을 검증하고 PUT 용 임시 URL 을 만듦.
     * 아직 저장소에 객체가 없으므로 목록에 넣지 않고 "대기" 로 둠.
     */
    public PendingUpload presignUpload(String name, String contentType) {
        validator.validateName(name);
        String id = UUID.randomUUID().toString();
        String key = storage.keyFor(id + "_" + name);
        String url = storage.uploadUrl(key, contentType, urlTtl)
                .orElseThrow(() -> new IllegalStateException("이 저장소는 직접 업로드를 지원하지 않음"));
        registry.putPending(new StoredFile(id, name, key, -1, contentType, Instant.now()));
        log.info("직접 업로드 준비 : {} → {}", name, key);
        return new PendingUpload(id, url, urlTtl.toSeconds());
    }

    /**
     * 직접 업로드 2단계. 브라우저가 PUT 을 마친 뒤 부름.
     * 저장소에 객체가 실제로 있는지(headObject) 확인하고 나서야 목록에 올림.
     * 브라우저 말만 믿고 올리면, 실패한 업로드가 목록에 남음.
     */
    @CacheEvict(value = "files", key = "'list'")
    public StoredFile completeUpload(String id) throws IOException {
        StoredFile pending = registry.takePending(id)
                .orElseThrow(() -> new NotFoundException("대기 중인 업로드 없음: " + id));
        long size = storage.size(pending.key())
                .orElseThrow(() -> new IllegalArgumentException("저장소에 객체가 없음. PUT 이 끝나지 않았거나 실패함"));
        StoredFile stored = new StoredFile(id, pending.originalName(), pending.key(), size,
                pending.contentType(), Instant.now());
        registry.put(stored);
        log.info("직접 업로드 완료 : {} ({} bytes)", stored.originalName(), size);
        return stored;
    }

    /** 1단계 결과. 컨트롤러가 응답으로 바꿈 */
    public record PendingUpload(String id, String url, long expiresIn) {
    }

    public List<StoredFile> list() {
        return registry.findAll();
    }

    /**
     * 캐시되는 목록 (cache-aside). 캐시에 있으면 DB 를 부르지 않고, 없으면 DB 에서 읽어 캐시에 넣음.
     * 캐시 키는 files::list — prod 에서는 redis-cli 로 그대로 찾을 수 있음 · 30초 뒤 만료(CacheConfig).
     * List 를 그대로 캐시하면 JSON 에서 되돌릴 때 원소 타입을 잃으므로 record 로 감쌈.
     */
    @Cacheable(value = "files", key = "'list'")
    public FileList listCached() {
        log.info("목록 조회 (DB)");
        // ArrayList 로 복사 — Stream.toList() 의 불변 목록은 JSON 에서 되돌릴 때 만들 수 없는 형식이라 피함
        return new FileList(new ArrayList<>(registry.findAll()));
    }

    /** 캐시에 들어가는 목록 묶음 */
    public record FileList(List<StoredFile> items) {
    }

    public StoredFile get(String id) {
        return registry.find(id).orElseThrow(() -> new NotFoundException("파일 없음: " + id));
    }

    /** 앱을 거쳐 내려받음. 디스크 · S3 모두 가능 */
    public Resource download(String id) throws IOException {
        return storage.load(get(id).key());
    }

    /** 저장소에서 직접 내려받는 임시 URL. 디스크 저장소는 비어 있음 */
    public Optional<String> downloadUrl(String id) {
        StoredFile file = get(id);
        return storage.downloadUrl(file.key(), file.originalName(), urlTtl);
    }

    public Duration urlTtl() {
        return urlTtl;
    }

    @CacheEvict(value = "files", key = "'list'")
    public void delete(String id) throws IOException {
        StoredFile file = get(id);
        storage.delete(file.key());
        registry.remove(id);
        log.info("삭제 : {} [{}]", file.originalName(), storage.kind());
    }
}
