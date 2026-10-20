# file-service API

모든 응답은 JSON. 실패는 `{status, code, message, timestamp}` 한 가지 형태.

## 01. 목록

| 방법 | 경로 | 설명 | 성공 |
| --- | --- | --- | --- |
| POST | `/api/files` | 업로드. multipart, 항목 이름 `file` | 201 + `Location` |
| GET | `/api/files` | 목록 (최근 업로드가 위) · 캐시 30초 · 업로드 · 완료 · 삭제 때 지움 (1.3.0) | 200 |
| GET | `/api/files/{id}` | 파일 정보 | 200 |
| GET | `/api/files/{id}/content` | 앱을 거쳐 내려받음 | 200 (바이너리) |
| GET | `/api/files/{id}/url` | 저장소에서 직접 받는 임시 URL | 200 / local 이면 409 |
| POST | `/api/files/presign` | 직접 업로드 1단계. JSON `{name, contentType}` → PUT 용 임시 URL (1.1.0) | 201 / local 이면 409 |
| POST | `/api/files/{id}/complete` | 직접 업로드 2단계. S3 에 객체가 있는지 확인 후 목록 등록 (1.1.0) | 200 |
| DELETE | `/api/files/{id}` | 삭제 | 204 |
| GET | `/api/files/{id}/events` | 이벤트 이력 · 다운로드 횟수. prod 는 DynamoDB (1.3.0) | 200 |
| GET | `/api/status` | 저장소 · 목록 DB · 캐시 · 이벤트 · 호스트명 · 파일 수 | 200 |
| GET | `/actuator/health` | 생존 확인. prod 에서는 DB(RDS) · S3 검사 포함 · 하나라도 실패하면 `DOWN` · 503 (1.2.0) · 캐시 · DynamoDB 는 넣지 않음 (1.3.0) | 200 `{"status":"UP"}` |
| GET | `/actuator/info` | 빌드 버전 · 시각 | 200 |

## 02. 실패 코드

| 상태 | code | 언제 |
| --- | --- | --- |
| 400 | `BAD_REQUEST` | 빈 파일 · 허용하지 않는 확장자 · 경로 문자 · (TODO 2) 업무 상한 초과 |
| 404 | `NOT_FOUND` | 목록에 없는 id |
| 409 | (본문 없음) | local 저장소에서 `/url` · `/presign` 요청 |
| 400 | `BAD_REQUEST` | `/complete` 를 PUT 전에 부름 (저장소에 객체 없음) |
| 404 | `NOT_FOUND` | `/complete` 를 두 번 부름 (대기 목록에 없음) |
| 413 | `PAYLOAD_TOO_LARGE` | 멀티파트 상한(10MB) 초과. 컨트롤러에 닿기 전에 거부됨 |
| 500 | `STORAGE_ERROR` | 디스크 · S3 입출력 실패. 사유는 서버 로그에만 남김 |

## 03. curl 예

```bash
BASE=http://localhost:8080          # 서버에서는 https://www.<student_id>.<instructor_domain>

# 업로드
echo "hello $(date)" > hello.txt
curl -s -F "file=@hello.txt" $BASE/api/files | tee up.json
ID=$(sed -E 's/.*"id":"([^"]+)".*/\1/' up.json)

# 목록 · 정보
curl -s $BASE/api/files
curl -s $BASE/api/files/$ID

# 앱 경유 다운로드
curl -s -o got.txt $BASE/api/files/$ID/content && cat got.txt

# 직접 다운로드 URL (S3 저장소만). 주소에 X-Amz-Expires=300 이 보임
curl -s $BASE/api/files/$ID/url

# 삭제
curl -s -o /dev/null -w "%{http_code}\n" -X DELETE $BASE/api/files/$ID   # 204

# 이벤트 이력 · 다운로드 횟수 (1.3.0)
curl -s $BASE/api/files/$ID/events

# 상태
curl -s $BASE/api/status
```

## 04. 응답 예

```json
// POST /api/files → 201
{
  "id": "7c2f1d0e-9a44-4b3f-8f1a-2b9c6d5e4f30",
  "name": "hello.txt",
  "size": 38,
  "contentType": "text/plain",
  "uploadedAt": "2026-10-13T01:12:45.120Z"
}

// GET /api/files/{id}/url → 200
{
  "id": "7c2f1d0e-9a44-4b3f-8f1a-2b9c6d5e4f30",
  "url": "https://cld01-files-20261013.s3.ap-northeast-2.amazonaws.com/uploads/7c2f...hello.txt?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Expires=300&...",
  "expiresIn": 300
}

// GET /api/files/{id}/events → 200 (1.3.0)
{
  "fileId": "7c2f1d0e-9a44-4b3f-8f1a-2b9c6d5e4f30",
  "downloads": 1,
  "events": [
    { "fileId": "7c2f…", "at": "2026-10-19T01:12:45.120Z#a1b2c3d4", "type": "UPLOAD",   "host": "ip-10-0-11-23" },
    { "fileId": "7c2f…", "at": "2026-10-19T01:13:02.871Z#5e6f7a8b", "type": "DOWNLOAD", "host": "ip-10-0-12-88" }
  ]
}

// GET /api/status → 200
{ "storage": "s3", "registry": "mysql", "cache": "redis", "events": "dynamodb", "host": "ip-10-0-11-23", "files": 3 }

// 실패 → 400
{ "status": 400, "code": "BAD_REQUEST", "message": "허용하지 않는 확장자: exe", "timestamp": "2026-10-13T10:12:45.12" }
```
