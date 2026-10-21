#!/bin/bash
# 퍼블릭 서브넷 · local 프로파일(H2 · 메모리 캐시 · 로그 기록기) — AWS 저장소 없이 뜸
set -euxo pipefail

export DEBIAN_FRONTEND=noninteractive
apt-get update -q
apt-get install -y -q docker.io docker-compose-v2
systemctl enable --now docker

# ubuntu 사용자가 sudo 없이 docker 를 쓰게 — Actions 가 ubuntu 로 SSH 접속해 compose 를 실행함
usermod -aG docker ubuntu

mkdir -p /opt/file-service
cd /opt/file-service

# 이미지 이름은 .env 의 APP_IMAGE 에서 — 배포 때마다 이 한 줄만 바뀜
cat > compose.yml <<'YAML'
services:
  app:
    image: ${APP_IMAGE}
    container_name: file-service
    restart: unless-stopped
    ports:
      - "80:8080"
    environment:
      SPRING_PROFILES_ACTIVE: local
      # 컨테이너 사용자(app)가 쓸 수 있는 곳에 업로드 파일을 둠
      FILE_LOCAL_PATH: /tmp/uploads
YAML

# 첫 부팅은 공용 이미지로 — 파이프라인이 첫 배포를 하기 전에도 화면이 뜸
echo "APP_IMAGE=superbdevops/file-service:1.3.0" > .env

chown -R ubuntu:ubuntu /opt/file-service
docker compose up -d