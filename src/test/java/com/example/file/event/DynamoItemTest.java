package com.example.file.event;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DynamoDB 항목 모양 테스트. toItem 은 AWS 를 부르지 않으므로 표 · 자격 증명 없이 확인함.
 * 표 키 설계(파티션 키 fileId · 정렬 키 at · TTL expireAt)가 코드와 같은지를 봄.
 */
class DynamoItemTest {

    private static final Instant NOW = Instant.parse("2026-10-19T01:02:03.456Z");

    // TODO 5 · DynamoFileEventRecorder.toItem 을 작성한 뒤 아래 주석을 풀면 통과해야 함
     @Test
     void 이벤트_항목은_키_설계대로_만들어짐() {
         Map<String, AttributeValue> item = DynamoFileEventRecorder.toItem("f-1", "UPLOAD", "ip-10-0-11-23", NOW);

         assertEquals(Set.of("fileId", "at", "type", "host", "expireAt"), item.keySet());   // 다섯 속성
         assertEquals("f-1", item.get("fileId").s());                                        // 파티션 키
         assertEquals("UPLOAD", item.get("type").s());
         assertEquals("ip-10-0-11-23", item.get("host").s());

         String at = item.get("at").s();                                                     // 정렬 키 = ISO 시각#난수 8자
         assertNotNull(at);
         assertTrue(at.startsWith("2026-10-19T01:02:03.456Z#"), at);
         assertEquals("2026-10-19T01:02:03.456Z#".length() + 8, at.length());

         String expireAt = item.get("expireAt").n();                                         // TTL = 숫자 · epoch 초 10자리
         assertNotNull(expireAt, "expireAt 은 숫자(N) 형식이어야 함 · fromN");
         assertEquals(10, expireAt.length(), "밀리초(13자리)가 아니라 초(10자리)");
         assertEquals(NOW.getEpochSecond() + 30L * 24 * 3600, Long.parseLong(expireAt));     // 30일 뒤

         Map<String, AttributeValue> again = DynamoFileEventRecorder.toItem("f-1", "UPLOAD", "ip-10-0-11-23", NOW);
         assertNotEquals(at, again.get("at").s(), "같은 밀리초라도 at 이 달라야 덮어쓰지 않음");
     }
}
