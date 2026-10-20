package com.example.file.api.dto;

import com.example.file.event.FileEventRecorder.FileEvent;

import java.util.List;

/**
 * 한 파일의 이벤트 이력.
 *
 * @param fileId     파일 id
 * @param downloads  다운로드 횟수 (prod 는 DynamoDB 의 at=STATS 항목)
 * @param events     이벤트 목록 · 시간순
 */
public record EventsResponse(String fileId, long downloads, List<FileEvent> events) {
}
