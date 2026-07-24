# 백엔드 공용 Dockerfile — 한 번의 빌드 스테이지에서 두 앱의 bootJar를 만들고,
# compose가 target(api|worker)으로 골라 쓴다.
#   docker build --target api -t mail-api .
#   docker build --target worker -t mail-worker .

# ── 빌드 스테이지: 저장소 전체를 넣고 두 jar를 한 번에 ──────────────
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
# 래퍼·빌드 스크립트를 먼저 복사해 의존성 다운로드 레이어를 캐시
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle/ gradle/
RUN sh gradlew --no-daemon --version
COPY mail-common/ mail-common/
COPY mail-core/ mail-core/
COPY infra/ infra/
COPY mail-api/ mail-api/
COPY mail-worker/ mail-worker/
COPY mail-admin/ mail-admin/
RUN sh gradlew --no-daemon :mail-api:bootJar :mail-worker:bootJar

# ── api 런타임 ──────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre AS api
# compose healthcheck 용 (jre 베이스 이미지엔 curl/wget 이 없다)
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /src/mail-api/build/libs/*.jar app.jar
# 업로드 이미지가 쌓이는 디렉터리(APP_UPLOADS_DIR 기본 "uploads") — compose가 볼륨을 붙인다
RUN mkdir -p /app/uploads /app/logs
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]

# ── worker 런타임 ───────────────────────────────────────────────────
FROM eclipse-temurin:21-jre AS worker
WORKDIR /app
COPY --from=build /src/mail-worker/build/libs/*.jar app.jar
RUN mkdir -p /app/logs
EXPOSE 8082
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
