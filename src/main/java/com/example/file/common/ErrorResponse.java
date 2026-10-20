package com.example.file.common;

import java.time.LocalDateTime;

/**
 * 실패 응답 형태. board-app 과 같은 모양.
 *
 * @param status    응답 코드
 * @param code      화면이 분기할 기준
 * @param message   사용자에게 표시할 문구
 * @param timestamp 발생 시각
 */
public record ErrorResponse(int status, String code, String message, LocalDateTime timestamp) {

    public static ErrorResponse of(int status, String code, String message) {
        return new ErrorResponse(status, code, message, LocalDateTime.now());
    }
}
