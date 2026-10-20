package com.example.file.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.util.Locale;
import java.util.Set;

/**
 * 업로드 파일 검증.
 *
 * 저장소(디스크 · S3)에 닿기 전에 거름. 거르지 않으면 실행 파일이 그대로 저장되고,
 * 큰 파일이 네트워크와 디스크를 점유함.
 *
 * 검증 실패는 IllegalArgumentException → GlobalExceptionHandler 가 400 BAD_REQUEST 로 바꿈.
 *
 * 순서 : 비어 있음 → 이름(경로 문자) → 확장자(TODO 1) → 크기(TODO 2)
 */
@Component
public class FileValidator {

    /** 허용 확장자. 소문자로 비교함 */
    static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "txt", "md", "csv", "json",
            "png", "jpg", "jpeg", "gif",
            "pdf", "zip");

    /** 업무 상한(바이트). application.yml 의 app.upload.max-bytes · 기본 10MB */
    private final long maxBytes;

    public FileValidator(@Value("${app.upload.max-bytes:10485760}") long maxBytes) {
        this.maxBytes = maxBytes;
    }

    /** 업로드 본문이 있을 때의 전체 검증. FileService.upload 가 저장 전에 호출함 */
    public void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("파일이 비어 있음");
        }
        validateName(file.getOriginalFilename());
        validateExtension(file.getOriginalFilename());
        validateSize(file);
    }

    /** 이름만 검증함. 직접 업로드(프리사인 PUT)는 본문이 서버에 오지 않으므로 이름만 볼 수 있음 */
    public void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("파일 이름이 없음");
        }
        if (name.contains("..") || name.contains("/") || name.contains("\\")) {
            // 경로 문자가 들어오면 저장 위치가 바뀔 수 있으므로 거부함
            throw new IllegalArgumentException("파일 이름에 경로 문자를 쓸 수 없음");
        }
    }

    /** 확장자가 ALLOWED_EXTENSIONS 에 없으면 거부함 */
    void validateExtension(String name) {
        String ext = extensionOf(name);
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException("허용하지 않는 확장자: " + ext);
        }
    }

    /**
     * 업무 상한 검증. 멀티파트 자체 상한(spring.servlet.multipart.max-file-size)은 413 으로 먼저 걸리므로,
     * 이 검증은 그보다 작은 업무 상한을 둘 때 400 으로 동작함.
     */
    void validateSize(MultipartFile file) {
        if (file.getSize() > maxBytes) {
            throw new IllegalArgumentException(
                    "파일 크기 초과: " + file.getSize() + " bytes (상한 " + maxBytes + ")");
        }
    }

    /** 확장자를 소문자로 돌려줌. 없으면 빈 문자열 */
    static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public long maxBytes() {
        return maxBytes;
    }
}
