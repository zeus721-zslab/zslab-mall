# zslab-mall Backend

Spring Boot 3.x · Java 21 · Gradle Kotlin DSL · MariaDB · Flyway

## 실행

루트 README.md "로컬 실행" 절을 따른다.

1. 루트 .env.example 을 루트 .env 로 복사 후 실 값 채움 (compose가 루트 .env 값을 backend 컨테이너에 전달)
2. 루트에서 docker compose -f docker-compose.mall.yml -f docker-compose.dev.yml up -d (개발용 backend 컨테이너가 Dockerfile.dev의 gradle bootRun으로 기동)

## 테스트

Gradle wrapper(gradlew·gradlew.bat·gradle/wrapper/) 포함. JDK 21·Docker(Testcontainers) 필요.

1. ./gradlew test

## 마이그레이션

Flyway 자동 적용 (src/main/resources/db/migration/V*__*.sql).

## 프로파일

- local (기본·로컬 개발)
- prod (운영)

SPRING_PROFILES_ACTIVE 환경변수로 전환.

## 의존성 버전 갱신

Spring Boot(3.4.1)·ulid-creator(5.2.3)·io.spring.dependency-management(1.1.7) 등은 build.gradle.kts에서 핀. mariadb-java-client·flyway·lombok은 Spring Boot BOM 관리. 갱신 시 호환성 확인 필요.
