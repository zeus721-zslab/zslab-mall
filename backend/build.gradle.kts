plugins {
    java
    id("org.springframework.boot") version "3.4.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.zslab.mall"
version = "0.1.0-SNAPSHOT"

// Spring Boot 3.4.1의 dependency-management가 testcontainers 1.20.4로 pin하므로 override.
// docker-java 3.5.x 도입 시 도커 Desktop 4.73.1(Engine 29.4.3)과 호환 진단.
extra["testcontainers.version"] = "1.21.4"

java {
    sourceCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.mariadb.jdbc:mariadb-java-client")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-mysql")
    implementation("com.github.f4b6a3:ulid-creator:5.2.3")
    implementation("net.logstash.logback:logstash-logback-encoder:8.0")
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:mariadb")
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
}
