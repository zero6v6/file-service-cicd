# 직접 업로드 (프리사인 PUT) — 1.1.0

브라우저가 파일 본문을 앱 서버가 아니라 S3 에 바로 올림. 앱 서버는 "URL 발급"과 "완료 확인"만 함.

## 01. 흐름

```
브라우저                         file-service (EC2)                    S3
   │  1) POST /api/files/presign  {name, contentType}                   │
   │ ───────────────────────────────> │                                  │
   │                                  │ validateName · keyFor · 서명      │
   │ <─────────────────────────────── │  201 {id, url, expiresIn}        │
   │                                  │                                  │
   │  2) PUT <url>  (본문 = 파일, Content-Type = 서명한 값)               │
   │ ─────────────────────────────────────────────────────────────────> │
   │ <───────────────────────────────────────────────────────────────── │ 200
   │                                  │                                  │
   │  3) POST /api/files/{id}/complete                                   │
   │ ───────────────────────────────> │  headObject (크기 확인)           │
   │                                  │ ───────────────────────────────> │
   │                                  │ <─────────────────────────────── │
   │ <─────────────────────────────── │  200 FileInfo (목록에 등록됨)     │
```

2) 는 EC2 를 거치지 않음. 100MB 를 올려도 EC2 대역폭 · 메모리 · 디스크를 쓰지 않음.
3) 이 있어야 하는 이유 : 브라우저가 "올렸다"고 말해도 서버는 믿지 않고 S3 에 물어봄(headObject). 실패한 업로드가 목록에 남지 않게 함.

## 02. 버킷 CORS (필수)

브라우저가 다른 출처(`https://www.<student_id>.<instructor_domain>` → `https://<버킷>.s3.ap-northeast-2.amazonaws.com`)로 PUT 을 보내므로, 버킷이 CORS 로 허락해야 함. 없으면 브라우저 콘솔에 `blocked by CORS policy` 가 뜨고 2) 가 실패함.

`[STEP 1] S3 > 버킷 > <내 버킷> > 권한 > CORS(Cross-origin 리소스 공유) > 편집`

```json
[
  {
    "AllowedHeaders": ["Content-Type"],
    "AllowedMethods": ["PUT"],
    "AllowedOrigins": ["https://www.<student_id>.<instructor_domain>"],
    "ExposeHeaders": ["ETag"],
    "MaxAgeSeconds": 300
  }
]
```

| 항목 | 값 | 이유 |
| --- | --- | --- |
| AllowedMethods | `PUT` 만 | 다운로드(프리사인 GET)는 페이지 이동이라 CORS 와 무관. GET 을 열 필요 없음 |
| AllowedOrigins | 내 도메인 하나 | `*` 로 두면 어느 사이트든 발급된 URL 로 PUT 가능. 발급 자체는 앱이 통제하지만 출처는 좁혀 둠 |
| AllowedHeaders | `Content-Type` | 서명에 넣은 헤더. 브라우저가 preflight 에서 물어봄 |

CORS 는 "브라우저 규칙"임. curl 로 PUT 하면 CORS 없이도 됨. 그래서 버킷 CORS 가 없어도 curl 테스트는 통과하고 브라우저만 실패함 — 트러블슈팅에서 자주 헷갈리는 지점.

## 03. 왜 Content-Type 을 서명에 넣는가

발급 때 `contentType` 을 서명에 포함했으므로 PUT 때 같은 값을 보내야 함. 다르면 `403 SignatureDoesNotMatch`.
서명에 넣지 않으면 "URL 을 받은 사람이 아무 형식이나 올릴 수 있음" 이 됨. 크기도 묶고 싶으면 `Content-Length` 를 서명에 넣거나, S3 POST 정책(`content-length-range`)을 씀. 이 과정에서는 다루지 않음.

## 04. 역할 권한

발급은 EC2 역할로 서명하므로, 역할에 `s3:PutObject`(uploads/*) 가 있어야 PUT 이 성공함. 3) 의 headObject 는 `s3:GetObject` 로 충분함. `<student_id>-app-policy` 그대로임.

## 05. curl 로 확인

```bash
BASE=https://www.<student_id>.<instructor_domain>
echo "direct $(date)" > direct.txt

# 1) 발급
curl -s -X POST $BASE/api/files/presign \
  -H 'Content-Type: application/json' \
  -d '{"name":"direct.txt","contentType":"text/plain"}' | tee presign.json
ID=$(sed -E 's/.*"id":"([^"]+)".*/\1/' presign.json)
URL=$(sed -E 's/.*"url":"([^"]+)".*/\1/' presign.json)

# 2) S3 로 PUT (호스트가 s3.ap-northeast-2.amazonaws.com 인 것을 볼 것)
curl -s -o /dev/null -w "%{http_code}\n" -X PUT -H 'Content-Type: text/plain' --data-binary @direct.txt "$URL"   # 200

# 3) 완료
curl -s -X POST $BASE/api/files/$ID/complete

# 2) 를 건너뛰고 3) 을 부르면 → 400 "저장소에 객체가 없음"
```

## 06. 확인 질문

1. 2) 의 PUT 요청은 ALB 접근 로그 · 앱 로그 어디에도 남지 않음. 그러면 "누가 언제 무엇을 올렸는지"는 어디서 보는가? (S3 서버 액세스 로그 · CloudTrail 데이터 이벤트)
2. 발급된 URL 을 다른 사람에게 보내면 그 사람도 올릴 수 있음. 막으려면 무엇을 짧게 해야 하는가?
3. 역할 세션이 1시간이고 `ttl-seconds` 를 7200 으로 두면 URL 은 몇 초 뒤에 죽는가?
