# lab-file-service

파일 업로드 · 목록 · 다운로드 · 삭제 서비스. 파일 본체는 내 PC 에서는 디스크, 서버에서는 S3, 파일 목록은 데이터베이스(내 PC H2 · 서버 RDS MySQL)에 두고, **이 태그부터 목록 캐시(내 PC 메모리 · 서버 ElastiCache)와 이벤트 기록(내 PC 로그 · 서버 DynamoDB)이 붙음.** 이 태그에서 새로 채울 곳은 TODO 5 하나 · TODO 1 ~ 4 는 채워진 상태로 제공됨.

## 구성

| 항목 | 값 |
| --- | --- |
| JDK | 21 |
| 프레임워크 | Spring Boot 4.1.0 · Spring Data JPA |
| 빌드 | Gradle Wrapper 8.14 · Groovy DSL |
| AWS SDK | AWS SDK for Java 2.x · `software.amazon.awssdk:s3` · `dynamodb` |
| 파일 본체 | 디스크 `local` · S3 `prod` |
| 파일 목록 | H2 메모리 `local` · RDS MySQL 8.4 `prod` |
| 연결 풀 | HikariCP (Spring Boot 기본) |
| 목록 캐시 | 메모리 `local` · ElastiCache Valkey `prod` · Spring Cache (`@Cacheable` · `@CacheEvict`) |
| 이벤트 기록 | 메모리 · 로그 `local` · DynamoDB `prod` |
| 포트 | 8080 · 컨테이너는 80 → 8080 |
| 패키지 | `com.example.file` |
| 앱 · 이미지 · 컨테이너 이름 | `file-service` |

**로그인 없음.** 파일 목록 · 직접 업로드 대기 행은 데이터베이스 표 `file_record` 에 둠 — 인스턴스가 몇 대든 같은 목록. 목록은 30초 캐시를 먼저 보고, 업로드 · 다운로드 · URL 발급 · 삭제는 이벤트로 남음.

## 문서

| 파일 | 담는 것 |
| --- | --- |
| `README.md` | 저장소 구성 · 실행 · 태그 |
| `TODO.md` | 채울 자리 · 주석 읽는 법 · 확인 기준 |
| `docs/api.md` | 주소 · 요청 · 응답 · 실패 코드 |
| `docs/presign-put.md` | 직접 업로드 3단계 · 버킷 CORS |
| `docs/rds.md` | 목록을 RDS 로 · 관리형 비밀 · 템플릿 user-data · 연결 풀 (1.2.0) |
| `docs/cache-events.md` | 목록 캐시 · 이벤트 기록 · 역할 정책 · 템플릿 v6 · 저장소가 멈추면 (1.3.0) |

## 태그

주제 순서대로 코드가 자람. 태그를 옮기면 그 주제 시점의 코드가 됨.

| 주제 | 태그 | 추가되는 것 |
| --- | --- | --- |
| 파일 서비스와 디스크 저장소 | `file-storage-local` | 디스크 저장소 · 업로드 · 목록 · 다운로드 · 삭제 · 화면 · 검증 (TODO 1 · 2) · `FileValidatorTest` |
| S3 저장소와 역할 | `file-storage-s3` | S3 저장소 `prod` · IAM 역할로 접근 · S3 상태 지표 |
| 프리사인 다운로드 | `file-storage-presign-get` | `/api/files/{id}/url` · TODO 3 · `FileServiceTest` |
| 컨테이너 이미지와 배포 설정 | `file-deploy-container` | Dockerfile · compose · `.env.example` · 이미지 `1.0.0` |
| 직접 업로드와 무중단 배포 | `file-storage-presign-put` | `/presign` → PUT → `/complete` · 버킷 CORS · 이미지 `1.1.0` |
| 파일 목록을 RDS 로 | `file-registry-rds` | `FileRegistry` Map → JPA · `FileRecord` · `FileRecordRepository` · H2 / MySQL · TODO 4 · `FileRegistryTest` · 이미지 `1.2.0` |
| 목록 캐시와 이벤트 기록 | `file-cache-events` | `CacheConfig` · `@Cacheable` 목록 · `event/` 기록기(local 로그 · prod DynamoDB) · `/api/files/{id}/events` · TODO 5 · `DynamoItemTest` · `ListCacheTest` · 이미지 `1.3.0` |

```
git clone https://github.com/<github_org>/lab-file-service.git
cd lab-file-service
git checkout file-storage-local
git diff file-storage-local file-storage-s3 --stat
```

**`--stat` 이 다음 주제에서 바뀌는 파일 목록.** 컨트롤러가 목록에 없으면 규약이 지켜진 것.

이미 clone 한 저장소에 새 태그가 추가되면 받아서 그 태그에서 내 브랜치를 만듦. `main` 에서 작업하지 않으므로 충돌이 없음.

```
git fetch origin --tags
git switch -c my-cache file-cache-events
git diff file-registry-rds file-cache-events --stat
```

## 실행

```
./gradlew bootRun
```

```
http://localhost:8080
```

| 확인 | 값 |
| --- | --- |
| 상단 상태 줄 | `storage=local  registry=h2  cache=simple  events=log  host=…  files=0` |
| 업로드한 파일 | `./data/uploads/` |
| `GET /api/status` | `{"storage":"local","registry":"h2","cache":"simple","events":"log","host":"…","files":0}` |
| 기동 로그 | `Hibernate: create table file_record …` — H2 에 표 생성 · 앱을 끄면 목록이 사라짐(파일 본체는 디스크에 남음) |
| 목록을 연달아 새로 고침 | 로그 `목록 조회 (DB)` 가 처음 한 번만 · 업로드하면 다시 한 번 (캐시를 지웠으므로) |
| 업로드 · 다운로드 | 로그 `이벤트 : UPLOAD …` · `이벤트 : DOWNLOAD …` · `GET /api/files/{id}/events` |

처음 기동은 의존성 내려받기로 1 ~ 3분 걸림. 업로드 경로는 `application-local.yml` 의 `file.local.path`.

```
./gradlew test
```

## 서버 실행

공용 이미지 `<dockerhub_id>/file-service:1.3.0` 을 그대로 씀. **학습자는 빌드하지 않음.** `<dockerhub_id>` 는 수업에서 공유되는 Docker Hub 계정.

서버에서는 시작 템플릿 user-data 가 `compose.yml` · `.env` 를 만들고, DB 암호는 **Secrets Manager 의 RDS 관리형 비밀을 인스턴스 역할로 읽어** `.env` 에 넣음 · 사람이 암호를 적지 않음. user-data 전체와 연결 풀 설정은 `docs/rds.md` · 캐시 · 이벤트 · 템플릿 v6 은 `docs/cache-events.md`.

| 확인 | 값 |
| --- | --- |
| `docker compose logs -f` | `S3 저장소 준비 : s3://<bucket>/uploads/` |
| `curl -s localhost/actuator/health` | `{"status":"UP"}` |
| `curl -s localhost/api/status` | `{"storage":"s3","registry":"mysql","cache":"redis","events":"dynamodb", …}` · 인스턴스 두 대의 `files` 가 같음 |

| 프로파일 | 사용처 | 저장소 | AWS 자격 증명 |
| --- | --- | --- | --- |
| `local` | 내 PC | 디스크 `file.local.path` · 목록 H2 메모리 · 캐시 메모리 · 이벤트 로그 | 불필요 |
| `prod` | EC2 · 컨테이너 | S3 `APP_S3_BUCKET` · 목록 RDS `DB_URL` · 캐시 `REDIS_HOST` · 이벤트 `APP_EVENTS_TABLE` | EC2 역할 · DB 암호는 Secrets Manager → 역할 → `.env` · 코드 · 설정 · user-data 에 키 · 암호 없음 |

**`prod` 에서 `APP_S3_BUCKET` · `DB_URL` · `REDIS_HOST` · `APP_EVENTS_TABLE` 이 비어 있으면 기동 실패.** 잘못된 설정으로 조용히 기동되는 것을 막기 위한 동작.

## 무엇을 채우는가

**이 태그에서 새로 채울 곳은 TODO 5 하나.** TODO 1 ~ 4 는 앞 태그에서 작성한 결과가 채워져 있음 — 본인이 쓴 코드와 `git diff` 로 비교함.

| 번호 | 파일 · 메서드 | 하는 일 | 확인 |
| --- | --- | --- | --- |
| 1 (채워짐) | `common/FileValidator.validateExtension` | 허용하지 않는 확장자 거부 | `run.exe` 업로드가 400 · `FileValidatorTest` |
| 2 (채워짐) | `common/FileValidator.validateSize` | 업무 상한 초과 거부 | `FileValidatorTest` |
| 3 (채워짐) | `service/FileService.downloadUrl` | 목록에서 찾아 저장소의 프리사인 URL 을 돌려줌 | `FileServiceTest` · 서버에서 `/url` 200 |
| 4 (채워짐) | `service/FileRegistry.takePending` | 직접 업로드 대기 행을 DB 에서 찾아 지우며 돌려줌 | `FileRegistryTest` · 서버에서 직접 업로드 3단계가 인스턴스 2대에서 완료 |
| **5** | `event/DynamoFileEventRecorder.toItem` | 이벤트 한 건을 DynamoDB 항목(`fileId` · `at` · `type` · `host` · `expireAt`)으로 만듦 | `DynamoItemTest` · 서버에서 DynamoDB 항목 탐색에 이벤트가 보임 |

```
./gradlew test
```

주석 읽는 법과 확인 기준은 `TODO.md`. **먼저 직접 쓰고 테스트로 확인한 뒤, 수업에서 같이 작성한 결과와 비교함.**

## 구조

```
  com.example.file
  ├─ FileServiceApplication        기동
  ├─ api
  │  ├─ FileController             표현 계층 · /api/files · /api/status
  │  └─ dto                        FileInfo · StatusResponse · EventsResponse · DownloadUrlResponse · PresignUpload*
  ├─ service
  │  ├─ FileService                검증 → 저장 → 목록 등록 · listCached @Cacheable · 바꾸는 메서드 @CacheEvict
  │  ├─ FileRegistry               파일 목록 · JPA · takePending (TODO 4 · 채워짐)
  │  ├─ FileRecord                 @Entity file_record · status PENDING · STORED
  │  └─ FileRecordRepository       JpaRepository · 메서드 이름이 쿼리
  ├─ storage
  │  ├─ FileStorage                저장소 규약 · save · load · delete · downloadUrl · uploadUrl · size
  │  ├─ LocalFileStorage           @Profile("local") · 디스크
  │  ├─ S3FileStorage              @Profile("prod") · S3 · 프리사인 GET · PUT
  │  └─ StoredFile                 저장된 파일 한 건
  ├─ event
  │  ├─ FileEventRecorder          이벤트 기록 규약 · record · events · downloads
  │  ├─ LoggingFileEventRecorder   @Profile("local") · 메모리 · 로그
  │  └─ DynamoFileEventRecorder    @Profile("prod") · DynamoDB · toItem (TODO 5) · PutItem · ADD downloads · Query
  ├─ config
  │  ├─ S3Config                   @Profile("prod") · S3Client · S3Presigner · 키 없음 → 역할
  │  └─ CacheConfig                @EnableCaching · TTL 30초 · JSON · 캐시 오류는 DB 로
  └─ common
     ├─ FileValidator              이름 · 확장자(TODO 1) · 크기(TODO 2) 검증
     ├─ S3HealthIndicator          @Profile("prod") · /actuator/health 의 s3 항목
     ├─ GlobalExceptionHandler     400 · 404 · 413 · 500 을 한 형태로
     ├─ ErrorResponse
     └─ NotFoundException

  src/test/java/com/example/file
  ├─ common/FileValidatorTest        TODO 1 · 2 확인
  ├─ service/FileServiceTest         TODO 3 확인 · 가짜 저장소(FakeStorage) · @SpringBootTest (목록이 DB)
  ├─ service/FileRegistryTest        TODO 4 확인 · H2
  ├─ service/ListCacheTest           캐시 두 번째 조회 · 지운 뒤 다시 읽기
  ├─ event/DynamoItemTest            TODO 5 확인 · AWS 없이 항목 모양만
  ├─ event/FileEventRecorderTest     local 기록기 규약
  └─ FileServiceApplicationTest     컨텍스트 기동
```

## 요청 흐름

```
  브라우저
     │  POST /api/files
     ▼
  FileController
     │
     ▼
  FileService ──▶ FileValidator          확장자 · 크기
     │
     ├──▶ FileStorage.save()             local  ./data/uploads/<id>_<name>
     │                                   prod   s3://<bucket>/uploads/<id>_<name>
     └──▶ FileRegistry.put()             local  H2 file_record
                                         prod   RDS MySQL files.file_record

  성공하면  FileController ──▶ FileEventRecorder.record()   local 로그 · prod DynamoDB PutItem
  목록 조회 FileController ──▶ FileService.listCached()     캐시(files::list) → 없으면 RDS
```

```
  브라우저
     │  GET /api/files/{id}/url
     ▼
  FileController ──▶ FileService.downloadUrl() ──▶ S3FileStorage.downloadUrl()
     │
     ▼  { url: https://<bucket>.s3…?X-Amz-Expires=300 }
  브라우저 ──GET <url>──▶ S3                 앱 서버를 거치지 않음
```

## 알아 둘 것

**컨트롤러는 `FileStorage` 규약만 호출함.** `LocalFileStorage` · `S3FileStorage` 중 어느 구현이 주입되는지는 프로파일이 지정함. 저장소가 바뀌어도 컨트롤러 · 화면 · 검증 코드의 diff 는 0 줄.

**파일 목록은 이 태그부터 데이터베이스에 있음.** 앞 태그(`file-storage-presign-put`)까지는 메모리 Map 이라 인스턴스가 둘이면 목록이 서로 달랐음. 이제 `FileRegistry` 의 메서드 이름은 그대로 두고 안쪽만 JPA 로 바꿨으므로 `FileService` 는 한 줄도 바뀌지 않았고, 인스턴스 두 대가 같은 표를 봄. 화면 상단 `registry=` · `host=` · `files=` 로 확인 가능.

**같은 코드, 다른 DB.** `local` 은 H2 메모리(설치 불필요), `prod` 는 `${DB_URL}` 의 RDS MySQL. 표는 `ddl-auto: ${DDL_AUTO:update}` — 없으면 만들고 있으면 건드리지 않음 · 운영은 `validate` + 마이그레이션 도구.

**캐시는 선택 저장소.** 원본은 RDS 에 있으므로 캐시가 없어져도 답은 같고 느려질 뿐. 그래서 캐시 오류는 로그만 남기고 DB 로 가며(`CacheConfig` 의 오류 처리기), `/actuator/health` 에서 Redis 검사를 뺐음(`management.health.redis.enabled: false`) — 캐시 장애가 ALB unhealthy · 인스턴스 교체로 번지지 않게. 자세한 것은 `docs/cache-events.md`.

**이벤트는 DynamoDB 키 설계 그대로.** 파티션 키 `fileId` · 정렬 키 `at`(ISO 시각 + `#` + 난수) · TTL `expireAt`(epoch 초). 다운로드 횟수는 같은 파티션의 `at = STATS` 항목에 원자 카운터로 둠. 지금은 기록이 실패하면 요청도 500 — 수업의 설계 과제.

**S3 접근에 키가 없음.** `S3Config` 는 자격 증명을 지정하지 않음. SDK 의 기본 자격 증명 체인이 환경 변수 → 프로파일 파일 → 인스턴스 메타데이터(EC2 역할) 순으로 탐색함.

**프리사인 URL 은 역할의 권한을 빌려 주는 것.** 서명한 쪽(EC2 역할)의 권한으로 그 객체 하나를 그 시간(기본 300초) 동안 받을 수 있음. 버킷은 계속 비공개.

| | `/content` 앱 경유 | `/url` 프리사인 |
| --- | --- | --- |
| 데이터 경로 | S3 → EC2 → 브라우저 | S3 → 브라우저 |
| EC2 대역폭 · 메모리 | 사용 | 미사용 |
| 접근 제어 | 요청마다 앱이 판단 | 발급 시 한 번 판단 · 이후 S3 가 서명만 확인 |

**직접 업로드는 3단계.** `POST /api/files/presign` → 브라우저가 S3 로 `PUT` → `POST /api/files/{id}/complete`. 본문은 EC2 를 거치지 않음. 완료 단계에서 서버가 `headObject` 로 객체를 확인한 뒤 목록에 올림. 자세한 것은 `docs/presign-put.md`.

## 막혔을 때

| 증상 | 확인 |
| --- | --- |
| `./gradlew: Permission denied` | `chmod +x gradlew` |
| 기동 중 `Port 8080 was already in use` | 다른 앱이 8080 사용 · `application-local.yml` 의 `server.port` |
| 업로드 400 | 응답의 `message` · 확장자 · 빈 파일 · 경로 문자 |
| 업로드 413 | 멀티파트 상한 10MB · `spring.servlet.multipart.max-file-size` |
| `prod` 기동 실패 · `APP_S3_BUCKET` | `.env` 의 버킷 이름 |
| `health` 가 `DOWN` · `S3Exception 403` | 역할 정책의 `s3:ListBucket` · 버킷 ARN |
| `NoSuchBucketException` | 버킷 이름 오타 |
| `SdkClientException … connect timed out` | 프라이빗 서브넷에서 S3 로 가는 경로 · 게이트웨이 엔드포인트 · NAT |
| `Unable to load credentials` | 인스턴스에 역할 미연결 |
| `/url` 이 409 | `local` 프로파일 · 디스크 저장소에는 프리사인 없음 |
| `bootRun` 이 `DB_URL` · 드라이버 관련 오류 | 내 PC 에 `SPRING_PROFILES_ACTIVE=prod` 가 남아 있음 · 지정하지 않으면 `local` |
| 로컬에서 다시 띄우면 목록 0 | 정상 · H2 메모리 DB |
| `FileRegistryTest` 두 번째 `takePending` 실패 | 행을 지우지 않음 · `findByIdAndStatus(id, PENDING)` · `ifPresent(r -> repository.delete(r))` |
| 서버 `Communications link failure` | `DB_URL` 엔드포인트 오타 · RDS `시작 중` · `db-sg 3306 ← app-sg` |
| 서버 `Access denied for user 'admin'` | `.env` 의 암호가 비었거나 옛 비밀 ARN · `docs/rds.md` |
| 서버 `registry=h2` | `.env` 에 `SPRING_PROFILES_ACTIVE=prod` 없음 |
| `DynamoItemTest` 실패 · `expireAt` 13자리 | `toEpochMilli()` 를 씀 · `getEpochSecond()` |
| `DynamoItemTest` 실패 · `expireAt 은 숫자(N)` | `fromS` 로 넣음 · `fromN` |
| 서버 로그 `캐시 읽기 실패 (DB 로 감)` 가 계속 | `REDIS_HOST` 오타 · `:6379` 까지 넣음 · `cache-sg` 인바운드 `app-sg` 6379 |
| 서버 업로드 500 · `AccessDeniedException … dynamodb` | 역할 정책 `WriteFileEvents` · 표 ARN · `docs/cache-events.md` |

---

&copy; SUPERB DEVOPS. All rights reserved.
