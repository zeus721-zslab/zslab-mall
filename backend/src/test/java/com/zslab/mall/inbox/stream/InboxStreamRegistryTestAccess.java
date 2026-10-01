package com.zslab.mall.inbox.stream;

/**
 * 등록소의 package-private 조회를 다른 테스트 패키지에서 쓰기 위한 같은 패키지 접근점(테스트 전용). 운영 코드에 공개 메서드를 늘리지 않는다.
 */
public final class InboxStreamRegistryTestAccess {

    private InboxStreamRegistryTestAccess() {
    }

    public static int connectionCount(InboxStreamRegistry registry) {
        return registry.connectionCount();
    }

    /** 전 연결에 하트비트를 보낸다 — 클라이언트가 떠난 연결은 쓰기에서 드러나 정리된다. */
    public static void sendHeartbeat(InboxStreamRegistry registry) {
        registry.sendHeartbeat();
    }
}
