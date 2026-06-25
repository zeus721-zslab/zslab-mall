package com.zslab.mall.common.util;

import com.github.f4b6a3.ulid.UlidCreator;

/**
 * 외부 노출 식별자 public_id 생성기. 형식: {@code <prefix>_<ULID>} = prefix 3 + '_' 1 + ULID 26 = CHAR(30).
 *
 * <p>prefix 명단 12종(ADR-001·V1 DDL): usr·slr·prd·var·ord·oit·pay·dlv·clm·rfn·att·aud.
 * Monotonic ULID로 동일 밀리초 내 단조 증가를 보장해 B-Tree 인덱스 효율을 확보한다.
 */
public final class PublicIdGenerator {

    private static final int PREFIX_LENGTH = 3;

    private PublicIdGenerator() {
    }

    /**
     * prefix와 Monotonic ULID를 결합한 public_id를 생성한다.
     *
     * @param prefix 3자 도메인 prefix(예: "ord"·"pay")
     * @return {@code <prefix>_<ULID>} 형식의 30자 식별자
     * @throws IllegalArgumentException prefix가 null·blank이거나 길이가 3이 아닌 경우
     */
    public static String generate(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("public_id prefix는 null·blank일 수 없습니다.");
        }
        if (prefix.length() != PREFIX_LENGTH) {
            throw new IllegalArgumentException(
                    "public_id prefix는 3자여야 합니다(CHAR(30) = prefix 3 + '_' 1 + ULID 26). 입력: " + prefix);
        }
        return prefix + "_" + UlidCreator.getMonotonicUlid().toString();
    }
}
