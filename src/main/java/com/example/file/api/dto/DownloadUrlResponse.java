package com.example.file.api.dto;

/**
 * 프리사인 다운로드 URL 응답.
 *
 * @param id         파일 id
 * @param url        저장소에서 직접 받는 임시 주소
 * @param expiresIn  유효 시간(초)
 */
public record DownloadUrlResponse(String id, String url, long expiresIn) {
}
