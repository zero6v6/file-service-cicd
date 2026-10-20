package com.example.file.common;

/** 요청한 파일이 목록에 없을 때. 전역 처리기에서 404 로 변환됨 */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
