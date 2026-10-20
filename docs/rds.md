# 파일 목록을 RDS 로 — 1.2.0

파일 목록 · 직접 업로드 대기 행을 인스턴스 메모리에서 RDS MySQL 표 `file_record` 로 옮김. 코드 쪽 변경은 `FileRegistry` 안쪽뿐이고, 서버 쪽은 **비밀 · 역할 · 시작 템플릿** 세 군데만 바뀜.

## 01. 무엇이 어디에 있나

```
  사용자 ──▶ ALB ──▶ ASG · EC2 × 2 · file-service:1.2.0
                         │  파일 본체   ──▶ S3 (역할)
                         │  파일 목록   ──▶ RDS MySQL files.file_record (격리 서브넷 · db-sg 3306 ← app-sg)
                         └─ 부팅 1회    ──▶ Secrets Manager rds!db-… (역할 · GetSecretValue) → .env
```

| 상태 | 1.1.0 | 1.2.0 |
| --- | --- | --- |
| 파일 본체 | S3 | S3 |
| 파일 목록 | 인스턴스 메모리 · 인스턴스마다 다름 | RDS `file_record` · 모든 인스턴스가 같음 |
| 직접 업로드 대기 | 인스턴스 메모리 · 1단계 · 3단계가 다른 인스턴스면 실패 | RDS `file_record` · `status PENDING` |
| DB 암호 | — | Secrets Manager → 역할 → `.env` (root · 600) |

## 02. 표

관리형 데이터베이스 주제에서 만든 표와 엔티티 `FileRecord` 가 1:1. 앱은 `ddl-auto: update` 라 표가 있으면 건드리지 않음.

```sql
CREATE TABLE file_record (
  id            VARCHAR(36)  NOT NULL PRIMARY KEY,
  original_name VARCHAR(255) NOT NULL,
  storage_key   VARCHAR(512) NOT NULL,
  size          BIGINT       NOT NULL,
  content_type  VARCHAR(100),
  uploaded_at   DATETIME(6)  NOT NULL,
  status        VARCHAR(10)  NOT NULL
);
```

| 필드 | 열 | 비고 |
| --- | --- | --- |
| `id` | `id` | 앱이 만드는 UUID |
| `originalName` · `storageKey` · `contentType` · `uploadedAt` | snake_case 열 | Spring Boot 기본 명명 규칙 |
| `status` | `status` | `@Enumerated(EnumType.STRING)` · `'PENDING'` · `'STORED'` |

## 03. 관리형 비밀 · 역할 정책

RDS 의 자격 증명 관리가 **AWS Secrets Manager 에서 관리** 여야 함(RDS 구성 탭 > 마스터 자격 증명 ARN 이 보임). 자체 관리였다가 되돌리면 비밀이 새로 생기고 ARN 이 바뀜 · 그 ARN 을 아래 정책과 user-data 에 씀. 비밀의 값은 콘솔에서 열지 않음.

```json
{
  "Sid": "ReadDbSecret",
  "Effect": "Allow",
  "Action": "secretsmanager:GetSecretValue",
  "Resource": "<secret_arn>"
}
```

| 항목 | 설명 |
| --- | --- |
| 붙이는 곳 | 역할 `<student_id>-ec2-role` 의 정책 `<student_id>-app-policy` · 기존 S3 문장 뒤 · 새 버전을 기본으로 |
| `Resource` | 이 비밀 하나 · `*` 이면 계정의 모든 비밀 |
| KMS | `aws/secretsmanager` 키는 별도 `kms:Decrypt` 불필요 · 고객 관리형 키면 필요 |

## 04. 시작 템플릿 user-data — v4

Ubuntu 24.04 는 `apt` 에 `awscli` 가 없으므로 `snap` 으로 설치함. 비밀을 다루는 구간은 `set +x` 로 로그에 남기지 않음.

```bash
#!/bin/bash
# 현재 위치: 시작 템플릿 v4 user-data (인스턴스 첫 부팅, root)
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
    image: <dockerhub_id>/file-service:1.2.0
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
| `snap install aws-cli --classic` | AWS CLI v2 · 실행 파일 `/snap/bin/aws` · NAT 경유 |
| `set +x` … `set -x` | 비밀 구간의 명령을 `cloud-init-output.log` 에 남기지 않음 |
| `get-secret-value` | 역할 자격 증명(IMDS)으로 읽음 · 키 파일 없음 · CloudTrail 에 역할 이름으로 기록 |
| `cat > .env <<EOF` (따옴표 없음) | `${DB_USERNAME}` · `$(hostname)` 치환 · `compose.yml` 은 `<<'EOF'` 로 그대로 |
| `DB_PASSWORD='…'` | 암호의 `$` 등을 compose 가 다른 변수로 바꾸지 않게 |
| `umask 077` · `chmod 600` | `.env` 를 root 만 읽음 |

ASG 는 시작 템플릿 버전을 **숫자(4)로 지정** 함 · `Latest` 를 쓰지 않음.

## 05. 연결 풀 — v5 (RDS 한도에 맞추기)

HikariCP 기본은 인스턴스당 최대 10 연결 · 놀 때도 10 을 열어 둠 · 연결 대기 30초. RDS `max_connections` 는 파라미터 그룹의 `{DBInstanceClassMemory/12582880}` 공식이라 작은 인스턴스일수록 작음. 이미지를 다시 빌드하지 않고 `.env` 세 줄로 맞춤.

```bash
# 현재 위치: 시작 템플릿 v5 user-data — .env 블록 안, DB_PASSWORD 줄 아래에 추가
SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE=5
SPRING_DATASOURCE_HIKARI_MINIMUMIDLE=2
SPRING_DATASOURCE_HIKARI_CONNECTIONTIMEOUT=3000
```

| 설정 | 기본 | v5 | 이유 |
| --- | --- | --- | --- |
| 최대 크기 | 10 | 5 | (ASG 최대 4 + 배포 여유 2) × 5 = 30 · `max_connections` 의 80% 안쪽 |
| 최소 유휴 | 최대와 같음 | 2 | 요청이 없을 때 2 대 × 2 = 4 연결 |
| 연결 대기 | 30000ms | 3000ms | DB 가 멈추면 요청이 30초씩 쌓이지 않고 3초 안에 실패 · `/actuator/health` 가 ALB 응답 대기(5초) 안에 답함 |

## 06. 확인

| 확인 | 기대 |
| --- | --- |
| `GET /api/status` 네 번 | `registry=mysql` · `host` 는 번갈아 · `files` 는 같음 |
| `GET /actuator/info` | `version` `1.2.0` |
| Session Manager → `mysql -h <rds_endpoint> -u admin files` (암호는 역할로 읽어 `MYSQL_PWD` 로만) | `SELECT … FROM file_record` 에 업로드한 행 · `SHOW PROCESSLIST` 에 앱 연결 |
| RDS 모니터링 탭 `DatabaseConnections` | v4 약 20 · v5 약 4 |
| `sudo ls -l /opt/file-service/.env` | `-rw------- root root` |
| `sudo grep -c 'DB_PASSWORD=' /var/log/cloud-init-output.log` | `0` |

## 07. DB 가 멈추면

`/actuator/health` 는 DB 검사를 포함하므로 RDS 재부팅 동안 `DOWN`(503). 타깃이 모두 unhealthy 면 ALB 는 그래도 모든 대상에 보냄(fail-open) → 사용자는 500. ASG 상태 확인이 ELB 이면 인스턴스 교체까지 번질 수 있음. DB 작업 전에는 ASG 의 `ReplaceUnhealthy` 를 일시 중지하는 것이 실무 절차.

## 08. 막혔을 때

| 증상 | 확인 |
| --- | --- |
| `AccessDeniedException … GetSecretValue` | 정책 문장 · ARN 오타 · 옛 ARN · 새 버전이 기본이 아님 |
| `aws: command not found` | `snap install` 실패 · NAT |
| `Communications link failure` | `DB_URL` 엔드포인트 · RDS `시작 중` · `db-sg` |
| `Access denied for user 'admin'` | `.env` 의 암호가 빔 · `.env` heredoc 에 `'EOF'` |
| `Unknown database 'files'` | RDS 생성 때 초기 DB 이름을 비움 · `CREATE DATABASE files;` |
| `registry=h2` | `.env` 에 `SPRING_PROFILES_ACTIVE=prod` 없음 |
| `files` 가 host 마다 다름 | 옛 버전 인스턴스가 섞임 · ASG 템플릿 버전 |

---

&copy; SUPERB DEVOPS. All rights reserved.
