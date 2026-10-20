package com.example.file.reference;

import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * [참고용 · 실행하지 않음] DynamoDB 를 AWS SDK for Java v2 로 다루는 기초 문법.
 *
 * 테스트가 아님 — @Test 가 없어 gradlew test 가 실행하지 않고, 이미지에도 들어가지 않음(src/test).
 * 컴파일만 되므로 SDK 버전이 바뀌어 문법이 깨지면 빌드에서 바로 드러남.
 *
 * 읽는 순서 : 0 준비 → 1 Create → 2 Read → 3 Update → 4 Delete → 5 오늘 코드 → 6 응용
 * 예시 표는 오늘 표와 같은 모양 — 파티션 키 fileId (S) · 정렬 키 at (S)
 *
 *   콘솔에서 한 일          SDK 요청            클라이언트 메서드
 *   항목 생성               PutItemRequest      dynamo.putItem(…)
 *   항목 하나 열기          GetItemRequest      dynamo.getItem(…)
 *   항목 탐색 > 쿼리        QueryRequest        dynamo.query(…)
 *   항목 편집               UpdateItemRequest   dynamo.updateItem(…)
 *   항목 삭제               DeleteItemRequest   dynamo.deleteItem(…)
 *
 * 모든 요청의 모양은 같음 : XxxRequest.builder() → .tableName(…) → 키 · 값 · 식 → .build() → dynamo.xxx(요청)
 */
final class DynamoDbBasics {

    private DynamoDbBasics() {
    }

    // =====================================================================================
    // 0 준비 — 클라이언트 · 값(AttributeValue) · 항목(Map)
    // =====================================================================================

    /**
     * 기능      클라이언트 하나 만들기. 스레드 안전 → 앱 전체에서 하나를 재사용함(요청마다 만들지 않음)
     * 활용      자격 증명은 코드에 없음 — 기본 자격 증명 체인이 EC2 역할의 임시 키를 찾음
     * 동작결과  리전만 정하면 끝. 서버에서는 역할 정책에 쓸 동작 · 표 ARN 이 있어야 함
     */
    static DynamoDbClient client() {
        return DynamoDbClient.builder()
                .region(Region.AP_NORTHEAST_2)
                .build();
    }

    /**
     * 기능      값 하나 = AttributeValue. 형식(S · N · BOOL · L · M)을 값과 함께 붙임
     * 활용      콘솔에서 속성 추가할 때 고른 "String · Number · Boolean · List · Map" 과 같음
     * 동작결과  숫자도 문자열로 넘김(fromN("3")) — DynamoDB 가 정밀도를 잃지 않게
     */
    static Map<String, AttributeValue> valueKinds() {
        return Map.of(
                "name", AttributeValue.fromS("hello.txt"),                     // S  문자열
                "size", AttributeValue.fromN("1024"),                          // N  숫자 (문자열로)
                "public", AttributeValue.fromBool(false),                      // BOOL
                "tags", AttributeValue.fromL(List.of(AttributeValue.fromS("a"), AttributeValue.fromS("b"))), // L 목록
                "meta", AttributeValue.fromM(Map.of("ext", AttributeValue.fromS("txt"))));                  // M 맵
    }

    /** 키 = 파티션 키 + 정렬 키. Get · Update · Delete 는 항목 전체가 아니라 이 키 Map 만 넘김 */
    static Map<String, AttributeValue> key(String fileId, String at) {
        return Map.of(
                "fileId", AttributeValue.fromS(fileId),
                "at", AttributeValue.fromS(at));
    }

    // =====================================================================================
    // 1 Create — PutItem
    // =====================================================================================

    /**
     * 기능      항목 하나 넣기
     * 활용      .item(Map) 에 키 속성 둘 + 나머지 속성
     * 동작결과  같은 키가 이미 있으면 **아무 말 없이 통째로 덮어씀** (콘솔 "항목 생성" 과 같음)
     *           → 덮어쓰기를 막으려면 5 의 조건부 쓰기
     */
    static void create(DynamoDbClient dynamo, String table, String fileId, String at) {
        Map<String, AttributeValue> item = new HashMap<>(key(fileId, at));
        item.put("type", AttributeValue.fromS("UPLOAD"));
        item.put("note", AttributeValue.fromS("첫 기록"));

        dynamo.putItem(PutItemRequest.builder()
                .tableName(table)
                .item(item)
                .build());
    }

    // =====================================================================================
    // 2 Read — GetItem (키로 하나) · Query (파티션 안 여러 개)
    // =====================================================================================

    /**
     * 기능      키로 항목 하나 읽기
     * 활용      .key(Map) 에 파티션 키 + 정렬 키 둘 다 (하나만 주면 ValidationException)
     * 동작결과  없으면 null 이 아니라 **빈 Map** → hasItem() 으로 확인
     *           값은 .s() · .n() 으로 꺼냄 — .n() 도 문자열이므로 숫자로 바꿔 씀
     */
    static String readOne(DynamoDbClient dynamo, String table, String fileId, String at) {
        GetItemResponse res = dynamo.getItem(GetItemRequest.builder()
                .tableName(table)
                .key(key(fileId, at))
                .build());

        if (!res.hasItem()) {
            return null;
        }
        Map<String, AttributeValue> item = res.item();
        AttributeValue note = item.get("note");
        return note == null ? null : note.s();
    }

    /**
     * 기능      한 파티션(같은 fileId) 안의 항목들을 정렬 키 순서로 읽기
     * 활용      keyConditionExpression — 파티션 키 = 는 반드시 하나 · 정렬 키는 = < > BETWEEN begins_with 중 하나
     *           식 안의 값은 :이름 으로 쓰고 expressionAttributeValues 에서 채움 (SQL 의 ? 바인딩과 같은 이유)
     * 동작결과  List<Map<String, AttributeValue>> · 없으면 빈 목록
     *           파티션 키 없이 "모든 파일의 …" 는 Query 로 못 함 → GSI 또는 Scan(표 전체 읽기 · 비쌈)
     */
    static List<Map<String, AttributeValue>> readMany(DynamoDbClient dynamo, String table, String fileId) {
        QueryResponse res = dynamo.query(QueryRequest.builder()
                .tableName(table)
                .keyConditionExpression("fileId = :f")
                .expressionAttributeValues(Map.of(":f", AttributeValue.fromS(fileId)))
                .build());
        return res.items();
    }

    // =====================================================================================
    // 3 Update — UpdateItem
    // =====================================================================================

    /**
     * 기능      항목의 일부 속성만 바꾸기 (항목 전체를 다시 쓰지 않음)
     * 활용      updateExpression 의 네 동작
     *             SET    #n = :v          값 넣기 · 바꾸기
     *             REMOVE #n               속성 빼기
     *             ADD    #n :one          숫자 더하기 (5 의 원자 카운터)
     *             DELETE #s :x            집합(SS · NS)에서 원소 빼기
     *           returnValues(ALL_NEW) 를 주면 바뀐 뒤의 항목을 돌려받음
     * 동작결과  항목이 없으면 **새로 만듦**(upsert) — 없을 때 실패시키려면 조건식 attribute_exists(fileId)
     */
    static Map<String, AttributeValue> update(DynamoDbClient dynamo, String table, String fileId, String at) {
        UpdateItemResponse res = dynamo.updateItem(UpdateItemRequest.builder()
                .tableName(table)
                .key(key(fileId, at))
                .updateExpression("SET #note = :n, #type = :t REMOVE #old")
                // 식 안의 속성 이름은 #이름 으로 바꿔 쓰는 습관이 안전함 — 예약어(at · name · size · status 등 수백 개)와 겹쳐도 됨
                .expressionAttributeNames(Map.of("#note", "note", "#type", "type", "#old", "oldField"))
                .expressionAttributeValues(Map.of(
                        ":n", AttributeValue.fromS("고친 기록"),
                        ":t", AttributeValue.fromS("DOWNLOAD")))
                .returnValues(ReturnValue.ALL_NEW)
                .build());
        return res.attributes();
    }

    // =====================================================================================
    // 4 Delete — DeleteItem
    // =====================================================================================

    /**
     * 기능      키로 항목 하나 지우기
     * 활용      .key(Map) · returnValues(ALL_OLD) 를 주면 지워진 항목을 돌려받음
     * 동작결과  없는 키를 지워도 **오류가 아님**(조용히 성공) — 꼭 있어야 하면 조건식 attribute_exists(fileId)
     *           이 앱의 역할 정책에는 DeleteItem 이 없음 → 서버에서 부르면 AccessDenied (오래된 이벤트는 TTL 이 지움)
     */
    static void delete(DynamoDbClient dynamo, String table, String fileId, String at) {
        dynamo.deleteItem(DeleteItemRequest.builder()
                .tableName(table)
                .key(key(fileId, at))
                .build());
    }

    // =====================================================================================
    // 5 오늘 코드 — 기초에 조건 하나씩 더한 것 (DynamoFileEventRecorder)
    // =====================================================================================

    /**
     * 1 Create + 조건식 → 같은 키가 있으면 덮어쓰지 않고 ConditionalCheckFailedException
     * 조건은 "요청 키와 똑같은 항목 하나" 에만 검사됨 — 같은 fileId 의 다른 항목(at 이 다름)은 보지 않음
     */
    static boolean createOnlyIfAbsent(DynamoDbClient dynamo, String table, Map<String, AttributeValue> item) {
        try {
            dynamo.putItem(PutItemRequest.builder()
                    .tableName(table)
                    .item(item)
                    .conditionExpression("attribute_not_exists(fileId)")
                    .build());
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;                                                 // 이미 있음 · 아무것도 쓰지 않았음
        }
    }

    /** 3 Update + ADD → 읽고 더해서 쓰지 않고 DynamoDB 가 그 자리에서 1 을 더함 (항목이 없으면 0 에서 시작) */
    static void countUp(DynamoDbClient dynamo, String table, String fileId) {
        dynamo.updateItem(UpdateItemRequest.builder()
                .tableName(table)
                .key(key(fileId, "STATS"))
                .updateExpression("ADD downloads :one")
                .expressionAttributeValues(Map.of(":one", AttributeValue.fromN("1")))
                .build());
    }

    /** 2 Read(Query) + begins_with + #at → 정렬 키가 '20' 으로 시작하는 이벤트만 · at 은 예약어라 #at */
    static List<Map<String, AttributeValue>> eventsOnly(DynamoDbClient dynamo, String table, String fileId) {
        return dynamo.query(QueryRequest.builder()
                .tableName(table)
                .keyConditionExpression("fileId = :f AND begins_with(#at, :y)")
                .expressionAttributeNames(Map.of("#at", "at"))
                .expressionAttributeValues(Map.of(
                        ":f", AttributeValue.fromS(fileId),
                        ":y", AttributeValue.fromS("20")))
                .scanIndexForward(true)
                .limit(50)
                .build()).items();
    }

    // =====================================================================================
    // 6 응용 (참고) — 실무에서 바로 만나는 것
    // =====================================================================================

    /** 시간 상한 — 기본값이면 느릴 때 요청이 수십 초 매달릴 수 있음. 재시도 포함 전체 · 한 번 시도의 상한 */
    static DynamoDbClient clientWithTimeouts() {
        return DynamoDbClient.builder()
                .region(Region.AP_NORTHEAST_2)
                .overrideConfiguration(c -> c
                        .apiCallTimeout(Duration.ofSeconds(2))
                        .apiCallAttemptTimeout(Duration.ofMillis(800)))
                .build();
    }

    /** 강력한 일관 읽기 — 방금 쓴 값을 바로 읽어야 할 때. 읽기 비용 두 배 · GSI 에서는 쓸 수 없음 */
    static boolean readLatest(DynamoDbClient dynamo, String table, String fileId, String at) {
        return dynamo.getItem(GetItemRequest.builder()
                .tableName(table)
                .key(key(fileId, at))
                .consistentRead(true)
                .build()).hasItem();
    }

    /**
     * 낙관적 잠금 — version 이 내가 읽은 값일 때만 고치고 version + 1.
     * 동시에 고친 두 요청 중 하나만 성공 · 실패한 쪽은 다시 읽고 재시도
     */
    static boolean updateIfVersion(DynamoDbClient dynamo, String table, String fileId, String at, long readVersion) {
        try {
            dynamo.updateItem(UpdateItemRequest.builder()
                    .tableName(table)
                    .key(key(fileId, at))
                    .updateExpression("SET #note = :n, #ver = :next")
                    .conditionExpression("#ver = :old")
                    .expressionAttributeNames(Map.of("#note", "note", "#ver", "version"))
                    .expressionAttributeValues(Map.of(
                            ":n", AttributeValue.fromS("동시에 고쳐도 하나만"),
                            ":old", AttributeValue.fromN(Long.toString(readVersion)),
                            ":next", AttributeValue.fromN(Long.toString(readVersion + 1))))
                    .build());
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }

    /**
     * 다음 쪽 읽기 — Query 는 한 번에 최대 1 MB(또는 limit) 까지. 응답에 lastEvaluatedKey 가 있으면 더 있음
     * 직접 반복하는 모양. SDK 의 dynamo.queryPaginator(요청).items() 를 쓰면 이 반복을 대신 해 줌
     */
    static int countAll(DynamoDbClient dynamo, String table, String fileId) {
        int count = 0;
        Map<String, AttributeValue> start = null;
        do {
            QueryRequest.Builder b = QueryRequest.builder()
                    .tableName(table)
                    .keyConditionExpression("fileId = :f")
                    .expressionAttributeValues(Map.of(":f", AttributeValue.fromS(fileId)))
                    .limit(100);
            if (start != null) {
                b.exclusiveStartKey(start);
            }
            QueryResponse page = dynamo.query(b.build());
            count += page.count();
            start = page.hasLastEvaluatedKey() ? page.lastEvaluatedKey() : null;
        } while (start != null);
        return count;
    }

    /** 예외 — 무엇이 실패했는지 종류로 나눔. 오류 코드 문자열은 awsErrorDetails().errorCode() */
    static String explain(Runnable call) {
        try {
            call.run();
            return "성공";
        } catch (ConditionalCheckFailedException e) {
            return "조건식이 거짓 — 같은 키가 이미 있음 · version 이 바뀜";
        } catch (ResourceNotFoundException e) {
            return "표 이름 · 리전이 다름";
        } catch (DynamoDbException e) {
            // AccessDeniedException(역할 정책) · ValidationException(식 문법 · 예약어 · 키 형식) 등
            return "DynamoDB 가 거절 : " + e.awsErrorDetails().errorCode();
        } catch (SdkClientException e) {
            return "요청이 닿지 못함 — 자격 증명을 못 찾음 · 네트워크 · 시간 상한";
        }
    }
}
