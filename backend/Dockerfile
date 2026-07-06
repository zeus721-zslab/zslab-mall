# 운영용 멀티스테이지: gradle 빌드 → JRE 실행 (infra 04 정합)
FROM gradle:8-jdk21 AS build
WORKDIR /app
COPY . .
ENV GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx1g"
RUN gradle bootJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-Xmx512m", "-jar", "app.jar"]
