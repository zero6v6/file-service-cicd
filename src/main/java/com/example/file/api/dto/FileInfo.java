package com.example.file.api.dto;

import com.example.file.storage.StoredFile;

import java.time.Instant;

/** 화면에 보여 줄 파일 정보. 저장소 키(key)는 내부 값이므로 내보내지 않음 */
public record FileInfo(
        String id,
        String name,
        long size,
        String contentType,
        Instant uploadedAt) {

    public static FileInfo from(StoredFile f) {
        return new FileInfo(f.id(), f.originalName(), f.size(), f.contentType(), f.uploadedAt());
    }
}
