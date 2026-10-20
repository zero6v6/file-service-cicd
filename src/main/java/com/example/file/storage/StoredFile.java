package com.example.file.storage;

import java.time.Instant;

/**
 * 저장된 파일 하나의 정보.
 *
 * key 는 저장소 안의 위치임.
 *   local → 디스크 파일 이름
 *   prod  → S3 객체 키 (uploads/<uuid>_<원본명>)
 */
public record StoredFile(
        String id,
        String originalName,
        String key,
        long size,
        String contentType,
        Instant uploadedAt) {
}
