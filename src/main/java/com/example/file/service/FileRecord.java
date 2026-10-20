package com.example.file.service;

import com.example.file.storage.StoredFile;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 파일 목록의 한 행. 데이터베이스 표 file_record 와 1:1.
 *
 * 메모리 Map 이던 목록이 이제 DB 에 있으므로, 인스턴스가 몇 대든 · 다시 떠도 같은 목록을 봄.
 * status 는 직접 업로드의 "대기(PENDING)" 와 "저장됨(STORED)" 을 구분함.
 */
@Entity
@Table(name = "file_record")
public class FileRecord {

    public enum Status { PENDING, STORED }

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 255)
    private String originalName;

    @Column(nullable = false, length = 512)
    private String storageKey;

    @Column(nullable = false)
    private long size;

    @Column(length = 100)
    private String contentType;

    @Column(nullable = false)
    private Instant uploadedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;

    protected FileRecord() {
        // JPA 용
    }

    public static FileRecord from(StoredFile f, Status status) {
        FileRecord r = new FileRecord();
        r.id = f.id();
        r.originalName = f.originalName();
        r.storageKey = f.key();
        r.size = f.size();
        r.contentType = f.contentType();
        r.uploadedAt = f.uploadedAt();
        r.status = status;
        return r;
    }

    public StoredFile toStoredFile() {
        return new StoredFile(id, originalName, storageKey, size, contentType, uploadedAt);
    }

    public String getId() { return id; }
    public Status getStatus() { return status; }
    public Instant getUploadedAt() { return uploadedAt; }
}
