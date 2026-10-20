package com.example.file;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 파일 업로드 · 다운로드 · 삭제 · 목록 서비스.
 *
 * 저장소는 프로파일로 고름.
 *   local → 내 PC 의 디스크 (LocalFileStorage)
 *   prod  → S3 (S3FileStorage, 자격 증명은 EC2 인스턴스 프로파일)
 *
 * 코드는 같고 저장소만 바뀜. 자격 증명은 코드 어디에도 없음.
 */
@SpringBootApplication
public class FileServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FileServiceApplication.class, args);
    }
}
