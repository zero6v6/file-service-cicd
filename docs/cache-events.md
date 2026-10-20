# 목록 캐시와 이벤트 기록 — 1.3.0

클라우드 데이터베이스 2 주제에서 만들어 둔 **캐시 노드(ElastiCache Valkey)** 와 **이벤트 표(DynamoDB)** 에 앱이 붙음. 이제 파일 서비스는 바깥 저장소 네 곳을 씀. 코드는 캐시 표기 세 줄 · 이벤트 기록기 하나가 늘었고, 서버 쪽은 **역할 정책 한 문장 · 시작 템플릿 `.env` 세 줄** 만 바뀜.

## 01. 무엇이 어디에 있나

```
  사용자 ──▶ ALB ──▶ ASG · EC2 × 2 · file-service:1.3.0
                         │  파일 본체    ──▶ S3                      (역할)              필수
                         │  파일 목록    ──▶ RDS MySQL file_record   (db-sg 3306)         필수
                         │  목록 캐시    ──▶ ElastiCache Valkey      (cache-sg 6379)      선택
                         └─ 이벤트 기록  ──▶ DynamoDB file-events     (역할 · 게이트웨이 엔드포인트)
```

| 저장소 | 담는 것 | 없어지면 | `/actuator/health` |
| --- | --- | --- | --- |
| S3 | 파일 본체 | 업로드 · 다운로드 불가 | 포함 (`s3`) |
| RDS | 파일 목록 · 대기 행 | 목록 · 업로드 불가 | 포함 (`db`) |
| ElastiCache | 목록 캐시 `files::list` (30초) | **느려질 뿐 답은 같음** — 원본은 RDS | **제외** (`management.health.redis.enabled: false`) |
| DynamoDB | 업로드 · 다운로드 · URL 발급 · 삭제 이벤트 · 다운로드 횟수 | 지금 코드는 요청이 500 — 수업의 설계 과제 | 제외 |

## 02. 캐시 — cache-aside

```
  GET /api/files
     │
     ▼  FileService.listCached()   @Cacheable("files", key 'list')
  캐시에 files::list 가 있나? ── 있음 ──▶ 캐시 값을 그대로 돌려줌 (DB 안 감)
     │ 없음
     ▼
  RDS 에서 읽음 → 캐시에 넣음(30초) → 돌려줌

  업로드 · 직접 업로드 완료 · 삭제   @CacheEvict("files", key 'list')   → 성공한 뒤 files::list 를 지움
```

| 설정 | 값 | 이유 |
| --- | --- | --- |
| 캐시 이름 · 키 | `files` · `list` → Redis 키 `files::list` | `redis-cli GET files::list` 로 그대로 봄 |
| TTL | 30초 (`CacheConfig`) | 지우기가 실패해도 30초면 사라짐 |
| 저장 형식 | JSON (`RedisSerializer.json()`) | 사람이 읽을 수 있음 · `List` 는 `FileList` record 로 감쌈(원소 타입 보존) |
| 명령 제한 시간 | `spring.data.redis.timeout: 1s` · `connect-timeout: 1s` | 캐시가 멈췄을 때 요청이 오래 매달리지 않음 |
| 오류 처리 | `CacheErrorHandler` — 읽기 실패는 DB 로 · 쓰기 · 지우기 실패는 무시 | 캐시 장애가 500 이 되지 않음 |
| `local` | `spring.cache.type: simple` (메모리) | 내 PC 에 Redis 불필요 · 동작은 같음 |

## 03. 이벤트 — DynamoDB 항목

표 `<student_id>-file-events` — 파티션 키 `fileId`(S) · 정렬 키 `at`(S) · GSI `type-at-index` · TTL `expireAt`.

| 항목 | `fileId` | `at` | 그 밖의 속성 |
| --- | --- | --- | --- |
| 이벤트 | 파일 id | `2026-10-19T01:02:03.456Z#a1b2c3d4` (ISO 시각 + `#` + 난수 8자) | `type` · `host` · `expireAt`(N · epoch 초 · 30일 뒤) |
| 통계 | 파일 id | `STATS` | `downloads`(N) — 다운로드할 때마다 `ADD downloads :one` |

| 동작 | DynamoDB 요청 | 비고 |
| --- | --- | --- |
| 이벤트 쓰기 | `PutItem` + `attribute_not_exists(fileId)` | 같은 키가 있으면 덮어쓰지 않고 실패 |
| 다운로드 횟수 | `UpdateItem` `ADD downloads :one` | 원자 카운터 · 인스턴스 두 대가 동시에 와도 안전 |
| 이력 보기 | `Query` `fileId = :f AND begins_with(#at, '20')` | `at` 은 예약어라 `#at` · `STATS` 항목은 빠짐 |
| 횟수 보기 | `GetItem` `fileId` + `at = STATS` | — |

## 04. 역할 정책 — `WriteFileEvents`

역할 `<student_id>-ec2-role` 의 정책 `<student_id>-app-policy` 에 문장 하나를 더함(기존 S3 · `ReadDbSecret` 은 그대로 · 새 버전을 기본으로). **캐시에는 IAM 문장이 없음** — 인증 없는 캐시는 네트워크(격리 서브넷 · `cache-sg`)로 막음.

```json
{
  "Sid": "WriteFileEvents",
  "Effect": "Allow",
  "Action": [
    "dynamodb:PutItem",
    "dynamodb:UpdateItem",
    "dynamodb:Query",
    "dynamodb:GetItem"
  ],
  "Resource": "arn:aws:dynamodb:ap-northeast-2:<account_id>:table/<student_id>-file-events"
}
```

| 항목 | 설명 |
| --- | --- |
| `Action` | 앱이 쓰는 네 가지뿐 · `Scan` · `DeleteItem` · `DeleteTable` 없음 |
| `Resource` | 이 표 하나 · GSI 를 Query 하게 되면 `…/index/*` 를 따로 더함 |
| 경로 | 프라이빗 서브넷 → DynamoDB 게이트웨이 엔드포인트(클라우드 데이터베이스 2 주제) · NAT 를 거치지 않음 |

## 05. 시작 템플릿 user-data — v6

v5 를 소스로 새 버전을 만들고 두 곳만 바꿈: 이미지 태그 `1.3.0` · `.env` 끝에 세 줄.

```bash
#!/bin/bash
# 현재 위치: 시작 템플릿 v6 user-data (인스턴스 첫 부팅, root)
set -euxo pipefail

export DEBIAN_FRONTEND=noninteractive
apt-get update -q
apt-get install -y -q docker.io docker-compose-v2 jq
snap install aws-cli --classic
systemctl enable --now docker

mkdir -p /opt/file-service
cd /opt/file-service

cat > compose.yml <<'EOF'
services:
  app:
    image: <dockerhub_id>/file-service:1.3.0
    container_name: file-service
    hostname: ${APP_HOST:-file-service}
    restart: unless-stopped
    ports:
      - "80:8080"
    env_file:
      - .env
EOF

set +x
SECRET_JSON=$(/snap/bin/aws secretsmanager get-secret-value --region ap-northeast-2 \
  --secret-id '<secret_arn>' --query SecretString --output text)
DB_USERNAME=$(echo "$SECRET_JSON" | jq -r .username)
DB_PASSWORD=$(echo "$SECRET_JSON" | jq -r .password)

umask 077
cat > .env <<EOF
SPRING_PROFILES_ACTIVE=prod
APP_S3_BUCKET=<bucket_name>
AWS_REGION=ap-northeast-2
APP_HOST=$(hostname)
DB_URL=jdbc:mysql://<rds_endpoint>:3306/files?serverTimezone=Asia/Seoul&characterEncoding=UTF-8
DB_USERNAME=${DB_USERNAME}
DB_PASSWORD='${DB_PASSWORD}'
SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE=5
SPRING_DATASOURCE_HIKARI_MINIMUMIDLE=2
SPRING_DATASOURCE_HIKARI_CONNECTIONTIMEOUT=3000
REDIS_HOST=<cache_endpoint>
REDIS_PORT=6379
APP_EVENTS_TABLE=<student_id>-file-events
EOF
unset SECRET_JSON DB_PASSWORD
set -x
chmod 600 .env

systemctl stop nginx || true
systemctl disable nginx || true

docker compose up -d
```

| 줄 | 설명 |
| --- | --- |
| `image: …:1.3.0` | 캐시 · 이벤트가 들어간 이미지 |
| `REDIS_HOST=<cache_endpoint>` | ElastiCache > Valkey 캐시 > `<student_id>-cache` > **기본 엔드포인트** 의 주소 부분만(`:6379` 빼고) |
| `REDIS_PORT=6379` | 기본 포트 · `cache-sg` 인바운드와 같아야 함 |
| `APP_EVENTS_TABLE` | 표 이름 · 비어 있으면 기동 실패(잘못된 설정으로 조용히 뜨지 않게) |

## 06. 확인

| 확인 | 기대 |
| --- | --- |
| `GET /api/status` | `"cache":"redis"` · `"events":"dynamodb"` · `registry=mysql` |
| `GET /actuator/info` | `version` `1.3.0` |
| 목록을 연달아 두 번 | 앱 로그 `목록 조회 (DB)` 가 30초에 한 번만 · 두 번째부터는 캐시 |
| Session Manager → `redis-cli -h <cache_endpoint> GET files::list` | JSON 한 줄 · `TTL files::list` 가 30 이하로 줄어듦 |
| 업로드 · 다운로드 뒤 `GET /api/files/{id}/events` | `downloads` 증가 · `events` 에 `UPLOAD` · `DOWNLOAD` |
| DynamoDB > 항목 탐색 · `fileId` 로 쿼리 | 이벤트 항목들 + `at = STATS` 항목 하나 |

## 07. 저장소가 멈추면

| 멈추는 것 | 사용자 | health | 이유 |
| --- | --- | --- | --- |
| 캐시 노드 재부팅 | 목록 200 · 요청마다 1 ~ 2초 느려짐 | UP 그대로 | 선택 저장소 · 오류 처리기 · health 제외 |
| DynamoDB 권한 없음 | 업로드 · 다운로드 **500** (파일은 S3 · RDS 에 이미 저장됨) | UP 그대로 | 기록 실패가 요청 실패로 번짐 — 설계 과제 |
| RDS 재부팅 | 목록 500 (캐시에 있으면 30초 동안은 200) | DOWN | 필수 저장소 · 클라우드 데이터베이스 설계 및 테스트 1 의 결과 A · B · C |

## 08. 막혔을 때

| 증상 | 확인 |
| --- | --- |
| `cache=simple` | `.env` 에 `SPRING_PROFILES_ACTIVE=prod` 없음 · 옛 템플릿 버전 |
| 로그 `캐시 읽기 실패 (DB 로 감)` 가 계속 | `REDIS_HOST` 오타 · `:6379` 까지 넣음 · `cache-sg` 인바운드에 `app-sg` 6379 없음 · 노드 `사용 가능` 아님 |
| `NOAUTH` · `WRONGPASS` | 캐시 생성 때 액세스 제어를 켬 · 교안은 "없음" |
| 연결이 바로 끊김 | 캐시 생성 때 전송 중 암호화를 켬 · 교안은 끔 |
| 기동 실패 `app.events.table` | `.env` 에 `APP_EVENTS_TABLE` 없음 |
| 업로드 500 · `AccessDeniedException … dynamodb:PutItem` | 역할 정책 `WriteFileEvents` · 표 ARN 의 `<student_id>` · 새 버전이 기본이 아님 |
| 업로드 500 · `ResourceNotFoundException` | 표 이름 오타 · 리전 |
| 이력 500 · `ValidationException … reserved keyword` | 코드에서 `at` 을 `#at` 으로 바꾸지 않음(완성 이미지는 정상) |
| TTL 이 안 지워짐 | `expireAt` 이 밀리초 13자리 · 문자열(S) · TTL 꺼짐 |

---

&copy; SUPERB DEVOPS. All rights reserved.

## 09. 기초 문법 참고 코드 — `src/test/java/com/example/file/reference/`

실행하지 않는 참고용 클래스 둘. 테스트가 아니라(`@Test` 없음) `gradlew test` 가 돌리지 않고 이미지에도 들어가지 않음. 컴파일만 되므로 SDK 버전이 바뀌어 문법이 깨지면 빌드에서 드러남.

| 파일 | 내용 | 읽는 순서 |
| --- | --- | --- |
| `DynamoDbBasics.java` | AWS SDK for Java v2 — `DynamoDbClient` · `AttributeValue` · 요청 객체 다섯 | 0 준비 → 1 Create(`putItem`) → 2 Read(`getItem` · `query`) → 3 Update(`updateItem` SET · REMOVE) → 4 Delete(`deleteItem`) → 5 오늘 코드(조건부 쓰기 · `ADD` · `begins_with`) → 6 응용(시간 상한 · 일관 읽기 · 낙관적 잠금 · 다음 쪽 · 예외) |
| `ElastiCacheBasics.java` | Spring Data Redis — `StringRedisTemplate` · `CacheManager` · 캐시 표기 (AWS SDK 없음) | 1 Create(`SET … PX` · `SET NX`) → 2 Read(`GET` · `TTL`) → 3 Update(다시 `SET` · `INCR` · `EXPIRE`) → 4 Delete(`DEL`) → 5 오늘 코드(표기가 대신 부르는 명령) |

두 파일의 CRUD 번호가 같아서 "같은 넣기가 DynamoDB 는 요청 객체 + 키 Map, Redis 는 키 · 값 한 줄" 로 나란히 비교할 수 있음.
