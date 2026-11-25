# FamLog API 서버 이미지 (사양서 13장: 운영 Docker Compose, S22-1). 운영 구성은 infra/prod/
#   빌드: docker build -t famlog/api:local .
# 1단계에서 bootJar 를 만들고, 2단계는 JRE 와 계층별로 푼 jar 만 담는다 (의존성 계층이 캐시되어 배포가 빠르다)
FROM eclipse-temurin:21.0.9_10-jdk-noble AS build
WORKDIR /src
COPY gradlew settings.gradle build.gradle lombok.config ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon dependencies --configuration runtimeClasspath > /dev/null
COPY src/main ./src/main
RUN ./gradlew --no-daemon bootJar \
    && cp "$(ls build/libs/*.jar | grep -v -- '-plain.jar')" app.jar \
    && java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:21.0.9_10-jre-noble
RUN useradd --create-home --uid 10001 famlog
WORKDIR /app
COPY --from=build /src/extracted/dependencies/ ./
COPY --from=build /src/extracted/spring-boot-loader/ ./
COPY --from=build /src/extracted/snapshot-dependencies/ ./
COPY --from=build /src/extracted/application/ ./
USER famlog

ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080 8091
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=4 \
    CMD curl -fsS http://127.0.0.1:8080/readyz > /dev/null || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
