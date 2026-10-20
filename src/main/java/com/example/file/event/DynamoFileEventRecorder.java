package com.example.file.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * DynamoDB 이벤트 기록. prod 에서만.
 *
 * 표 <student_id>-file-events — 클라우드 데이터베이스 2 주제에서 만든 그대로
 *   파티션 키 fileId (S) · 정렬 키 at (S) · GSI type-at-index (type + at) · TTL expireAt (N, epoch 초)
 *
 *   이벤트 항목 : fileId · at = ISO 시각#난수 8자 · type · host · expireAt   → "이 파일의 이벤트를 시간순으로" 가 Query 한 번
 *   통계 항목   : fileId · at = STATS · downloads (N)                     → 다운로드 횟수의 원본 (캐시가 아니라 여기)
 *
 * 자격 증명은 S3 와 같이 EC2 역할. 역할 정책에 이 표 ARN 으로
 * dynamodb:PutItem · UpdateItem · Query · GetItem 이 있어야 함.
 */
@Component
@Profile("prod")
public class DynamoFileEventRecorder implements FileEventRecorder {

    private static final Logger log = LoggerFactory.getLogger(DynamoFileEventRecorder.class);

    /** 이벤트를 남겨 두는 기간. 지나면 DynamoDB TTL 이 지움(보통 며칠 안 · 정확한 시각은 보장하지 않음) */
    static final Duration KEEP = Duration.ofDays(30);

    /** 다운로드 횟수를 담는 항목의 정렬 키 */
    static final String STATS = "STATS";

    private final DynamoDbClient dynamo;
    private final String table;

    public DynamoFileEventRecorder(@Value("${app.events.table}") String table,
                                   @Value("${app.s3.region}") String region) {
        this.dynamo = DynamoDbClient.builder().region(Region.of(region)).build();
        this.table = table;
        log.info("DynamoDB 이벤트 표 : {}", table);
    }

    @Override
    public String kind() {
        return "dynamodb";
    }

    /**
     * 이벤트 한 건을 DynamoDB 항목(속성 이름 → 값)으로 만듦. AWS 를 부르지 않으므로 테스트로 확인 가능함.
     */
    static Map<String, AttributeValue> toItem(String fileId, String type, String host, Instant now) {
        // TODO 5 · 이벤트 항목 만들기 (키 설계를 코드로)
        //
        // 기능        다섯 속성을 가진 Map 을 돌려줌
        //             fileId   → 파티션 키 · 그대로
        //             at       → 정렬 키 · now 의 ISO 문자열 + "#" + 난수 8자 (같은 밀리초 두 건이 덮어쓰지 않게)
        //             type     → 이벤트 종류 · GSI type-at-index 의 파티션 키
        //             host     → 응답한 인스턴스
        //             expireAt → TTL · now + KEEP 을 epoch "초" 로 · 숫자(N) 형식
        // 활용        AttributeValue.fromS(String)                      → 문자열(S)
        //             AttributeValue.fromN(String)                      → 숫자(N) · 값은 문자열로 넘김
        //             now.toString()                                    → 2026-10-19T01:02:03.456Z
        //             UUID.randomUUID().toString().substring(0, 8)      → 난수 8자
        //             now.plus(KEEP).getEpochSecond()                   → 10자리 초 (toEpochMilli 의 13자리 밀리초가 아님)
        //             Map.of(k1, v1, k2, v2, …)                          → 다섯 쌍
        // 동작결과    DynamoItemTest 의 "이벤트_항목은_키_설계대로_만들어짐" 주석을 풀어 통과
        //             서버에서 업로드 · 다운로드 뒤 DynamoDB 콘솔 항목 탐색에 fileId · at · type · host · expireAt 이 보임
        String at = now.toString() + "#" + UUID.randomUUID().toString().substring(0, 8);
        return Map.of(
                "fileId", AttributeValue.fromS(fileId),
                "at", AttributeValue.fromS(at),
                "type", AttributeValue.fromS(type),
                "host", AttributeValue.fromS(host),
                "expireAt", AttributeValue.fromN(Long.toString(now.plus(KEEP).getEpochSecond()))

        );
    }

    @Override
    public void record(String fileId, String type, String host) {
        // 같은 키(fileId + at)가 이미 있으면 덮어쓰지 않고 실패하게 함 — 콘솔 "항목 생성" 과 다른 점
        dynamo.putItem(PutItemRequest.builder()
                .tableName(table)
                .item(toItem(fileId, type, host, Instant.now()))
                .conditionExpression("attribute_not_exists(fileId)")
                .build());

        if ("DOWNLOAD".equals(type)) {
            // 원자 카운터 — 읽고 더해서 쓰지 않고, DynamoDB 가 그 자리에서 1 을 더함(인스턴스 두 대가 동시에 와도 안전)
            dynamo.updateItem(UpdateItemRequest.builder()
                    .tableName(table)
                    .key(Map.of("fileId", AttributeValue.fromS(fileId), "at", AttributeValue.fromS(STATS)))
                    .updateExpression("ADD downloads :one")
                    .expressionAttributeValues(Map.of(":one", AttributeValue.fromN("1")))
                    .build());
        }
    }

    @Override
    public List<FileEvent> events(String fileId) {
        // at 은 DynamoDB 예약어라 #at 으로 바꿔 씀 · begins_with '20' 으로 STATS 항목을 뺌(이벤트만)
        QueryRequest q = QueryRequest.builder()
                .tableName(table)
                .keyConditionExpression("fileId = :f AND begins_with(#at, :y)")
                .expressionAttributeNames(Map.of("#at", "at"))
                .expressionAttributeValues(Map.of(
                        ":f", AttributeValue.fromS(fileId),
                        ":y", AttributeValue.fromS("20")))
                .scanIndexForward(true)
                .limit(50)
                .build();
        return dynamo.query(q).items().stream()
                .map(i -> new FileEvent(fileId, s(i, "at"), s(i, "type"), s(i, "host")))
                .toList();
    }

    @Override
    public long downloads(String fileId) {
        Map<String, AttributeValue> item = dynamo.getItem(GetItemRequest.builder()
                .tableName(table)
                .key(Map.of("fileId", AttributeValue.fromS(fileId), "at", AttributeValue.fromS(STATS)))
                .build()).item();
        if (item == null || !item.containsKey("downloads")) {
            return 0;
        }
        return Long.parseLong(item.get("downloads").n());
    }

    private static String s(Map<String, AttributeValue> item, String name) {
        AttributeValue v = item.get(name);
        return v == null ? null : v.s();
    }
}
