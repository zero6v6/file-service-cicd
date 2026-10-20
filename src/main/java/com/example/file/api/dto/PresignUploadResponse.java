package com.example.file.api.dto;

/**
 * 직접 업로드 1단계 응답.
 *
 * @param id         파일 id. 2단계(complete)에서 다시 보냄
 * @param url        브라우저가 파일 본문을 PUT 할 임시 주소
 * @param expiresIn  유효 시간(초)
 */
public record PresignUploadResponse(String id, String url, long expiresIn) {
}
