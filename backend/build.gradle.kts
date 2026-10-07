plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.zslab.mall"
version = "0.1.0-SNAPSHOT"

// D-233: Boot 4.1.1 BOM의 Tomcat 11.0.24에 남는 OSV 권고 3건(DIGEST·긴 경로 constraint·FORM 인증)이 11.0.26에서 0건.
extra["tomcat.version"] = "11.0.26"
// D-268: Boot 4.1.1 BOM의 Jackson 3.1.5·2.21.5에 남는 CVE 5건을 덮는 최소 패치(같은 minor). Boot 4.1.x는 4.1.1이 최신이라 Boot 업그레이드로 해결 불가.
extra["jackson-bom.version"] = "3.1.7"
extra["jackson-2-bom.version"] = "2.21.7"

java {
    sourceCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")
    // D-269: 이메일 실발송(SmtpNotificationSender·EMAIL_SENDER=smtp일 때만 사용). 기본 mock에서는 JavaMailSender를 쓰지 않는다.
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.mariadb.jdbc:mariadb-java-client")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-mysql")
    implementation("com.github.f4b6a3:ulid-creator:5.2.3")
    implementation("net.logstash.logback:logstash-logback-encoder:8.0")
    // Track 77: JDK ImageIO는 webp 미지원 → 읽기 전용 순수 Java 플러그인(BSD-3). 썸네일 쓰기는 JDK jpg/png만 사용(webp 썸네일은 png).
    implementation("com.twelvemonkeys.imageio:imageio-webp:3.12.0")
    // Track 106-1: 리뷰 사진 재인코딩 전 EXIF Orientation 판독(ImageReencoder). webp의 전이 의존과 같은 버전을 직접 선언한다.
    implementation("com.twelvemonkeys.imageio:imageio-metadata:3.12.0")
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
    testImplementation("org.springframework.boot:spring-boot-starter-micrometer-metrics-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-mariadb")
    // Track 106-1: 리뷰 요약 비동기 재계산(커밋 후 전용 실행기) 결과 대기. 버전은 Boot BOM 관리.
    testImplementation("org.awaitility:awaitility")
    // D-269: 비밀번호 재설정·기존 알림 메일의 실 SMTP 수신 검증(테스트 내 SMTP 서버).
    testImplementation("com.icegreen:greenmail-junit5:2.1.5")
}

tasks.test {
    useJUnitPlatform()
    // Track 38: SuperAdminBootstrapRunner는 매 기동 실행되며 SUPER_ADMIN 부재 + 필수 env blank 시 Fail Fast(기동 중단)한다.
    // 통합 테스트(@SpringBootTest)는 SUPER_ADMIN을 seed하지 않으므로 전 컨텍스트 기동이 막힌다. 테스트 전용 더미 부트스트랩
    // 자격을 주입해(운영 값 아님·프로파일 무관·40+ 테스트 개별 수정 회피) 각 컨텍스트가 실 startup 경로로 SUPER_ADMIN을 1회
    // 생성하게 한다. Runner 로직 자체(멱등·Fail Fast)는 SuperAdminBootstrapRunnerIntegrationTest가 수동 구성 Runner로 분리 검증한다.
    environment("ADMIN_BOOTSTRAP_EMAIL", "bootstrap-superadmin@zslab.test")
    environment("ADMIN_BOOTSTRAP_PASSWORD", "test-only-bootstrap-pw-please-ignore")
    // FE-04: 데모 카탈로그 시드(CatalogDemoSeedRunner)를 테스트에서 강제 OFF. @SpringBootTest 기동 시 실행돼 싱글톤 공유
    // 컨테이너에 데모 행을 커밋하면 전역 상태 단언 테스트(inventory.variant_id UNIQUE·product_variant 전역 count)가 깨진다.
    // 테스트는 profile=local이라 application-local.yml의 enabled:true를 로드하므로, 상위 우선순위 systemProperty로 명시 차단한다.
    systemProperty("catalog.demo-seed.enabled", "false")
    // Track 88: 전체 스위트(1114 tests·@SpringBootTest 컨텍스트 캐시 누적)가 Gradle 기본 테스트 워커 힙(512m)에서
    // OutOfMemoryError(Java heap space) → DB 소켓·컨텍스트 로드 연쇄 실패(41건)를 냈다. 테스트 JVM 힙을 명시한다.
    maxHeapSize = "2g"
}
