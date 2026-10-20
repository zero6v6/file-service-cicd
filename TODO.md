# TODO 안내

이 태그에서 비어 있는 메서드는 **TODO 5 하나** (`DynamoFileEventRecorder.toItem`). TODO 1 ~ 4 는 앞 태그에서 작성한 결과가 채워진 상태 — 본인 코드와 `git diff` 로 비교함. 1 ~ 4 의 설명은 참고용.

---

## 01. 주석 읽는 법

    void validateSize(MultipartFile file) {
        // TODO 2 · 크기 상한 검증
        //
        // 기능        file.getSize() 가 maxBytes 보다 크면 IllegalArgumentException
        // 활용        file.getSize() → long · maxBytes → 생성자에서 주입됨
        // 동작결과    상한 초과 업로드가 400 BAD_REQUEST · FileValidatorTest 통과
    }

| 줄 | 뜻 | 이렇게 씀 |
| --- | --- | --- |
| 기능 | 이 자리에서 만들 것 | 적힌 조건대로 판단함 |
| 활용 | 이미 있어서 가져다 쓸 것 · 형식은 `이름 → 반환 타입 · 설명` | 새로 만들지 않고 찾아 씀 |
| 동작결과 | 만들고 나면 이렇게 됨 | curl · 브라우저 · 테스트로 직접 확인 |

**다 만들면 주석을 지움.** IDE 아래쪽 도구 창의 `TODO` 탭에서 남은 개수를 확인 가능함.

---

## 02. 목록

| 번호 | 파일 · 메서드 | 하는 일 | 활용 | 동작결과 |
| --- | --- | --- | --- | --- |
| 1 | `common/FileValidator.validateExtension(String name)` | 허용하지 않는 확장자 거부 | `extensionOf(name)` · `ALLOWED_EXTENSIONS.contains(ext)` | `run.exe` 가 400 · `note.txt` 는 201 · `FileValidatorTest` 의 주석 처리된 테스트를 풀어 통과 |
| 2 | `common/FileValidator.validateSize(MultipartFile file)` | 업무 상한 초과 거부 | `file.getSize()` · `maxBytes` | 상한 초과가 400 · `FileValidatorTest` 의 주석 처리된 테스트를 풀어 통과 |
| 3 | `service/FileService.downloadUrl(String id)` | 목록에서 찾아 저장소의 프리사인 URL 을 돌려줌 | `get(id)` · `storage.downloadUrl(key, originalName, urlTtl)` | `FileServiceTest` 의 주석 처리된 테스트를 풀어 통과 · 서버에서 `/url` 200 |
| 4 | `service/FileRegistry.takePending(String id)` | PENDING 행을 id 로 찾아 지우며 `StoredFile` 로 돌려줌 · 없으면 empty | `repository.findByIdAndStatus(id, PENDING)` · `repository.delete(record)` · `FileRecord::toStoredFile` | `FileRegistryTest` 의 주석 처리된 테스트를 풀어 통과 · 서버에서 직접 업로드 3단계가 인스턴스 2대에서도 완료 |
| **5** | `event/DynamoFileEventRecorder.toItem(fileId, type, host, now)` | 이벤트 한 건을 DynamoDB 항목 Map 으로 만듦 · `at` = ISO 시각#난수 8자 · `expireAt` = 30일 뒤 epoch 초(N) | `AttributeValue.fromS` · `fromN` · `now.toString()` · `UUID…substring(0, 8)` · `now.plus(KEEP).getEpochSecond()` | `DynamoItemTest` 의 주석 처리된 테스트를 풀어 통과 · 서버에서 DynamoDB 항목 탐색에 이벤트가 보임 |

### 1 · 확장자 검증

**src/main/java/com/example/file/common/FileValidator.java**

```java
void validateExtension(String name) {
    // TODO 1 · 확장자 검증
    // 기능        name 의 확장자가 ALLOWED_EXTENSIONS 에 없으면 IllegalArgumentException
    // 활용        extensionOf(name) → 소문자 확장자 · ALLOWED_EXTENSIONS.contains(ext)
    // 동작결과    run.exe 업로드가 400 · FileValidatorTest 통과
}
```

```
./gradlew test
curl -s -o /dev/null -w "%{http_code}\n" -F "file=@run.exe" http://localhost:8080/api/files
```

| 입력 | 작성 전 | 작성 후 |
| --- | --- | --- |
| `note.txt` | 201 | 201 |
| `run.exe` | 201 (거르지 못함) | 400 `허용하지 않는 확장자: exe` |

**`FileValidatorTest` 의 `허용하지_않는_확장자는_거부함` 주석을 풀어야 검증됨.**

### 2 · 크기 상한 검증

같은 파일의 `validateSize`. 멀티파트 자체 상한(`spring.servlet.multipart.max-file-size` · 10MB)은 413 으로 먼저 걸리므로, 이 검증은 그보다 작은 **업무 상한** 을 둘 때 동작함.

```
./gradlew test
```

**`FileValidatorTest` 의 `상한을_넘는_파일은_거부함` 주석을 풀어야 검증됨.** 테스트는 `new FileValidator(4)` 로 상한을 4바이트로 두고 5바이트 파일을 넣음.

### 3 · 직접 다운로드 URL

**src/main/java/com/example/file/service/FileService.java**

```java
public Optional<String> downloadUrl(String id) {
    // TODO 3 · 직접 다운로드 URL
    // 기능        id 로 목록에서 찾고, 저장소에 프리사인 URL 을 요청해 그대로 돌려줌
    // 활용        get(id) → StoredFile · storage.downloadUrl(key, originalName, ttl) → Optional<String>
    // 동작결과    FileServiceTest 통과 · 서버(S3)에서 GET /api/files/{id}/url 이 200
    return Optional.empty();
}
```

| 확인 | 기대 |
| --- | --- |
| `./gradlew test` · `FileServiceTest` | 주석을 푼 테스트 통과 · 가짜 저장소가 만든 `https://fake/<key>?expires=300` 이 그대로 옴 |
| 내 PC `GET /api/files/{id}/url` | 409 · 디스크 저장소는 `Optional.empty()` 를 돌려주므로 작성 전후가 같음 |
| 서버(S3) `GET /api/files/{id}/url` | 200 `{url, expiresIn}` · 서버 이미지는 완성 코드로 빌드됨 |

**내 PC 에서는 테스트로만 확인됨.** 디스크 저장소는 프리사인 URL 을 만들 수 없으므로 작성 전후 모두 409. `FileServiceTest` 의 `FakeStorage` 가 그 자리를 대신함.

### 4 · 대기 행 꺼내기 (DB 버전)

**src/main/java/com/example/file/service/FileRegistry.java**

```java
public Optional<StoredFile> takePending(String id) {
    // TODO 4 · 대기 행 꺼내기 (DB 버전)
    // 기능        status 가 PENDING 인 행을 id 로 찾아 지우고, StoredFile 로 바꿔 돌려줌 · 없으면 Optional.empty()
    // 활용        repository.findByIdAndStatus(id, FileRecord.Status.PENDING) → Optional<FileRecord>
    //             repository.delete(record) → 행 삭제 · Optional.ifPresent 와 함께
    //             FileRecord::toStoredFile → Optional.map 으로 변환
    // 동작결과    FileRegistryTest 통과 · 서버에서 직접 업로드 3단계가 인스턴스 2대에서도 완료
    return Optional.empty();
}
```

```
./gradlew test --tests '*FileRegistryTest'
```

| 확인 | 기대 |
| --- | --- |
| `putPending` 뒤 `find` | 비어 있음 · 대기 행은 정식 목록이 아님 |
| 첫 `takePending` | 값 있음 |
| 두 번째 `takePending` | 비어 있음 · 행이 지워졌음 |

**`FileRegistryTest` 의 `대기_행은_한_번만_꺼내짐` 주석을 풀어야 검증됨.** 메모리 Map 버전의 `pending.remove(id)` 한 줄이 DB 에서는 "찾기 → 지우기 → 변환" 세 단계가 됨. 클래스의 `@Transactional` 이 셋을 한 단위로 묶음. 직접 업로드 1단계와 3단계를 다른 인스턴스가 받을 수 있으므로 대기 행도 DB 에 둠.

### 5 · 이벤트 항목 만들기 (키 설계를 코드로)

**src/main/java/com/example/file/event/DynamoFileEventRecorder.java**

```java
static Map<String, AttributeValue> toItem(String fileId, String type, String host, Instant now) {
    // TODO 5 · 이벤트 항목 만들기 (키 설계를 코드로)
    // 기능        다섯 속성을 가진 Map 을 돌려줌
    //             fileId   → 파티션 키 · 그대로
    //             at       → 정렬 키 · now 의 ISO 문자열 + "#" + 난수 8자
    //             type     → 이벤트 종류 · GSI type-at-index 의 파티션 키
    //             host     → 응답한 인스턴스
    //             expireAt → TTL · now + KEEP 을 epoch "초" 로 · 숫자(N) 형식
    // 활용        AttributeValue.fromS(String) · AttributeValue.fromN(String)
    //             now.toString() · UUID.randomUUID().toString().substring(0, 8)
    //             now.plus(KEEP).getEpochSecond() · Map.of(k1, v1, …)
    // 동작결과    DynamoItemTest 통과 · 서버에서 DynamoDB 항목 탐색에 이벤트가 보임
    return Map.of();
}
```

```
./gradlew test --tests '*DynamoItemTest'
```

| 확인 | 기대 |
| --- | --- |
| 속성 이름 | 정확히 `fileId` · `at` · `type` · `host` · `expireAt` 다섯 개 · 대소문자까지 표의 키와 같음 |
| `at` | `2026-10-19T01:02:03.456Z#` 로 시작 · 뒤에 8자 · 두 번 만들면 서로 다름 |
| `expireAt` | 숫자(N) · 10자리 · 테스트 시각 + 30일(2,592,000초) |

**`DynamoItemTest` 의 `이벤트_항목은_키_설계대로_만들어짐` 주석을 풀어야 검증됨.** `toItem` 은 AWS 를 부르지 않으므로 표 · 자격 증명 없이 내 PC 에서 확인함. 실제 쓰기(`PutItem`) · 다운로드 횟수(`UpdateItem ADD`) · 이력(`Query`)은 이미 채워진 `record` · `events` 에서 이 Map 을 씀.

| 어긋나면 | 서버에서 생기는 일 |
| --- | --- |
| 키 이름 오타(`fileid` · `At`) | `PutItem` 이 `ValidationException` — 표의 키와 다름 |
| `at` 에 난수 없음 | 같은 밀리초의 두 이벤트 중 하나가 조건부 쓰기에서 실패 |
| `expireAt` 을 밀리초 · 문자열로 | TTL 이 그 항목을 영원히 지우지 않음 |

---

## 03. 응답 형태

### FileInfo

    {
      "id": "b7f1…",
      "originalName": "hello.txt",
      "size": 42,
      "contentType": "text/plain",
      "uploadedAt": "2026-10-12T01:02:03Z"
    }

### DownloadUrlResponse

    {
      "url": "https://<bucket>.s3.ap-northeast-2.amazonaws.com/uploads/…?X-Amz-Expires=300&…",
      "expiresIn": 300
    }

### ErrorResponse

실패는 모두 이 형태로 옴.

    {
      "status": 400,
      "code": "BAD_REQUEST",
      "message": "허용하지 않는 확장자: exe",
      "timestamp": "2026-10-12T01:02:03Z"
    }

| 코드 | 응답 | 사유 |
| --- | --- | --- |
| `BAD_REQUEST` | 400 | 빈 파일 · 경로 문자 · 허용하지 않는 확장자 (TODO 1) · 업무 상한 초과 (TODO 2) |
| `NOT_FOUND` | 404 | 목록에 없는 id |
| `PAYLOAD_TOO_LARGE` | 413 | 멀티파트 상한 초과 · 컨트롤러에 닿기 전에 거부됨 |
| `STORAGE_ERROR` | 500 | 디스크 · S3 입출력 실패 |

---

## 04. 막혔을 때

**먼저 20분은 스스로 찾아봄.**

| 증상 | 확인 |
| --- | --- |
| `run.exe` 가 여전히 201 | `validateExtension` 본문이 비어 있음 · `contains` 앞의 `!` |
| 테스트가 빨강 | 주석을 푼 테스트가 `IllegalArgumentException` 을 기대함 · 다른 예외 타입이면 실패 |
| 테스트가 안 보임 | 테스트 메서드의 `// @Test` 주석도 풀었는지 |
| `validateSize` 를 썼는데 10MB 넘는 파일이 413 | 정상 · 멀티파트 상한이 먼저 · 테스트는 4바이트 상한으로 확인함 |
| `FileServiceTest` 가 `url.isPresent()` 에서 실패 | `return Optional.empty()` 가 그대로 남아 있음 |
| `/url` 이 내 PC 에서 409 | 정상 · 디스크 저장소 · 서버에서 확인 |
| `FileRegistryTest` 두 번째 `takePending` 에서 실패 | 행을 지우는 줄이 없음 · `findById` 만 써서 status 를 안 봄 |
| "reference to delete is ambiguous" | `repository::delete` 메서드 참조 → 람다 `r -> repository.delete(r)` |
| `DynamoItemTest` · 속성 이름 집합이 다름 | `Map.of` 의 키 철자 · 다섯 개 모두 넣었는지 |
| `DynamoItemTest` · `expireAt` 이 null | `fromS` 로 넣음 → `.n()` 이 null · `fromN` |
| `DynamoItemTest` · 길이 13 | `toEpochMilli()` · `getEpochSecond()` 로 |
| `Map.of` 에서 컴파일 오류 | 키 · 값을 번갈아 10개 · 값은 모두 `AttributeValue` |

---

&copy; SUPERB DEVOPS. All rights reserved.
