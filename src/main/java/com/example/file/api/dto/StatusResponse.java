package com.example.file.api.dto;

/**
 * 서비스 상태. 바깥 저장소 네 곳 · 어느 인스턴스인지를 한 줄로 봄.
 *
 * @param storage   local 또는 s3          (파일 본체)
 * @param registry  h2 또는 mysql          (파일 목록)
 * @param cache     simple 또는 redis      (목록 캐시)
 * @param events    log 또는 dynamodb      (이벤트 기록)
 * @param host      응답한 서버의 호스트명
 * @param files     목록에 있는 파일 수 (DB 이므로 모든 인스턴스가 같음)
 */
public record StatusResponse(String storage, String registry, String cache, String events, String host, int files) {
}
