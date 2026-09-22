# 운영용 멀티스테이지: gradle 빌드 → JRE 실행 (infra 04 정합)
FROM gradle:8-jdk21 AS build
WORKDIR /app
ENV GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx1g"
# 의존성 레이어 분리(Track 100) — 소스 1바이트 변경이 의존성 재다운로드를 유발하지 않도록 빌드 스크립트만 먼저 COPY하고
# 더미 main 클래스로 bootJar를 1회 돌려 compileClasspath·runtimeClasspath·annotationProcessor 아티팩트를 이 레이어에 굳힌다.
# `gradle dependencies`를 쓰지 않는 이유: 그래프 메타데이터만 받고 jar를 내려받지 않아 캐시 효과가 없다.
# 베이스 이미지 USER=root라 Gradle 캐시는 /root/.gradle에 쌓인다 — 이미지가 VOLUME으로 선언한 /home/gradle/.gradle에
# 쌓였다면 레이어 커밋 시 폐기돼 캐시가 남지 않는다(베이스 USER 변경 시 재검토).
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
RUN mkdir -p src/main/java \
    && printf 'public class Warmup { public static void main(String[] args) {} }\n' > src/main/java/Warmup.java \
    && gradle bootJar --no-daemon \
    && rm -rf src build
COPY . .
RUN gradle bootJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-Xmx512m", "-jar", "app.jar"]
