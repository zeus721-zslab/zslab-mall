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
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.mariadb.jdbc:mariadb-java-client")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-mysql")
    implementation("com.github.f4b6a3:ulid-creator:5.2.3")
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:mariadb")
}

tasks.test {
    useJUnitPlatform()
}
