package com.example.file.storage;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

/**
 * 파일 저장소 규약.
 *
 * 컨트롤러는 이 규약만 알고, 디스크인지 S3 인지는 모름.
 * 구현을 바꿔도 컨트롤러 · 화면 · 검증 코드는 그대로임.
 */
public interface FileStorage {

    /** 저장소 종류. 응답과 로그에 표시함. local 또는 s3 */
    String kind();

    /** 파일 이름으로 저장소 안의 위치(key)를 정함. S3 는 uploads/ 접두사를 붙임 */
    default String keyFor(String fileName) {
        return fileName;
    }

    /** 파일을 key 위치에 저장하고 그 key 를 돌려줌 */
    String save(MultipartFile file, String key) throws IOException;

    /** 저장된 파일을 스트림으로 읽음 */
    Resource load(String key) throws IOException;

    /** 저장된 파일을 지움. 없어도 예외를 내지 않음 */
    void delete(String key) throws IOException;

    /**
     * 클라이언트가 저장소에서 직접 받을 수 있는 임시 URL.
     * 디스크 저장소는 만들 수 없으므로 비어 있음.
     */
    default Optional<String> downloadUrl(String key, String originalName, Duration ttl) {
        return Optional.empty();
    }

    /**
     * 클라이언트가 저장소에 직접 올릴 수 있는 임시 URL (HTTP PUT).
     * 디스크 저장소는 만들 수 없으므로 비어 있음.
     */
    default Optional<String> uploadUrl(String key, String contentType, Duration ttl) {
        return Optional.empty();
    }

    /** 저장소에 실제로 있는 객체의 크기. 없으면 비어 있음. 직접 업로드 완료 확인에 씀 */
    default Optional<Long> size(String key) throws IOException {
        return Optional.empty();
    }
}
