package com.example.file.service;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** file_record 표 접근. 메서드 이름이 곧 쿼리임 (Spring Data JPA) */
public interface FileRecordRepository extends JpaRepository<FileRecord, String> {

    List<FileRecord> findAllByStatusOrderByUploadedAtDesc(FileRecord.Status status);

    Optional<FileRecord> findByIdAndStatus(String id, FileRecord.Status status);

    long countByStatus(FileRecord.Status status);
}
