package com.example.file.event;

import java.util.List;

/**
 * 파일 이벤트(업로드 · 직접 업로드 · 다운로드 · URL 발급 · 삭제) 기록 규약.
 *
 * 이벤트는 "많이 쓰고 · 파일 id 로만 찾고 · 한 달 뒤 버림" → 관계형 표보다 DynamoDB 가 맞음.
 *   local → LoggingFileEventRecorder  메모리 · 로그 (DynamoDB 불필요)
 *   prod  → DynamoFileEventRecorder   DynamoDB 표 <student_id>-file-events
 *
 * 컨트롤러는 이 규약만 부름. 어느 구현이 주입되는지는 프로파일이 정함 — FileStorage 와 같은 방식.
 */
public interface FileEventRecorder {

    /** /api/status 의 events 값. log 또는 dynamodb */
    String kind();

    /** 이벤트 한 건을 남김. type 은 UPLOAD · UPLOAD_DIRECT · DOWNLOAD · PRESIGN_GET · DELETE */
    void record(String fileId, String type, String host);

    /** 이 파일의 이벤트를 시간순으로 (최대 50건) */
    List<FileEvent> events(String fileId);

    /** 이 파일의 다운로드 횟수 */
    long downloads(String fileId);

    /** 이벤트 한 건. at 은 ISO-8601 시각 문자열 (DynamoDB 정렬 키) */
    record FileEvent(String fileId, String at, String type, String host) {
    }
}
