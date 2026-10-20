package com.example.file.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.io.IOException;

/**
 * 전역 예외 처리기.
 *
 * 컨트롤러가 예외를 잡지 않아도 여기서 받아 같은 형태로 변환함.
 * 좁은 예외를 먼저 두고, 마지막에 IOException 을 둠.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NotFoundException e) {
        log.debug("대상 부재: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(404, "NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(IllegalArgumentException e) {
        log.debug("검증 실패: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(400, "BAD_REQUEST", e.getMessage()));
    }

    /** 멀티파트 상한(spring.servlet.multipart.max-file-size)을 넘긴 경우. 컨트롤러에 닿기 전에 발생함 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleTooLarge(MaxUploadSizeExceededException e) {
        log.debug("크기 초과: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ErrorResponse.of(413, "PAYLOAD_TOO_LARGE", "업로드 상한을 넘김"));
    }

    /** 저장소 입출력 실패. 사유를 그대로 내보내면 경로나 버킷 이름이 드러나므로 요약만 담음 */
    @ExceptionHandler(IOException.class)
    public ResponseEntity<ErrorResponse> handleIo(IOException e) {
        log.error("저장소 입출력 실패", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(500, "STORAGE_ERROR", "저장소 처리 실패"));
    }
}
