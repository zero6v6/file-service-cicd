package com.example.file.api.dto;

/**
 * 직접 업로드 1단계 요청. 본문은 보내지 않고 이름과 형식만 알림.
 *
 * @param name        원본 파일 이름
 * @param contentType 브라우저가 PUT 할 때 보낼 Content-Type (서명에 포함됨)
 */
public record PresignUploadRequest(String name, String contentType) {
}
