# zslab-mall Backend

Spring Boot 3.x · Java 21 · Gradle Kotlin DSL · MariaDB · Flyway

## 실행 (Docker 트랙 머지 후)

1. backend/.env.example 을 backend/.env 로 복사 후 실 값 채움
2. Docker Compose로 backend 컨테이너 빌드·실행 (별도 트랙)

## 로컬 직접 실행 (JDK 21 설치 시)

1. Gradle wrapper 생성: gradle wrapper (Gradle 8.x 필요)
2. ./gradlew bootRun

> 로컬 PC JDK 미설치 상태. Gradle wrapper(gradlew·gradle/wrapper/) 미생성. Docker 트랙으로 빌드·실행 권장.

## 마이그레이션

Flyway 자동 적용 (src/main/resources/db/migration/V*__*.sql).

## 프로파일

- local (기본·로컬 개발)
- prod (운영)

SPRING_PROFILES_ACTIVE 환경변수로 전환.

## 의존성 버전 갱신

Spring Boot(3.4.1)·ulid-creator(5.2.3)·io.spring.dependency-management(1.1.7) 등은 build.gradle.kts에서 핀. mariadb-java-client·flyway·lombok은 Spring Boot BOM 관리. 갱신 시 호환성 확인 필요.
