package com.zslab.mall.support;

import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 전 테스트가 공유하는 단일 MariaDB 컨테이너(싱글톤·never stop·JVM 종료 시 정리).
 *
 * <p>Track 63: 기존에는 테스트 클래스마다 자체 {@code static MariaDBContainer}를 기동해 클래스 수만큼 컨테이너가 떴다.
 * 본 홀더로 일원화해 JVM당 컨테이너 1개만 기동하고, {@code @DynamicPropertySource}로 {@link #INSTANCE} 접속 정보를
 * 각 컨텍스트에 주입한다. Ryuk이 JVM 종료 시 컨테이너를 회수하므로 명시적 stop을 호출하지 않는다(never stop).
 */
public final class MariaDbTestContainer {

    public static final MariaDBContainer<?> INSTANCE;

    static {
        INSTANCE = new MariaDBContainer<>(DockerImageName.parse("mariadb:11.4"));
        INSTANCE.start();
    }

    private MariaDbTestContainer() {}
}
