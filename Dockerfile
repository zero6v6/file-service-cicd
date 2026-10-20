# syntax=docker/dockerfile:1
# 다단계 빌드 : 1단계에서 jar 를 만들고, 2단계(JRE 만 있는 작은 이미지)로 jar 만 옮김

# ---------- 1단계 : 빌드 ----------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# 의존성 내려받기를 먼저 해 두어, 소스만 바뀌면 이 층은 캐시에서 재사용됨
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies -q || true

COPY src ./src
RUN ./gradlew --no-daemon clean bootJar -x test \
 && cp build/libs/file-service-*.jar app.jar

# ---------- 2단계 : 실행 ----------
FROM eclipse-temurin:21-jre
WORKDIR /app

# root 로 띄우지 않음
RUN groupadd --system app && useradd --system --gid app --no-create-home app
COPY --from=build /workspace/app.jar app.jar
RUN chown app:app app.jar
USER app

EXPOSE 8080
ENV SPRING_PROFILES_ACTIVE=prod
ENTRYPOINT ["java", "-jar", "app.jar"]
