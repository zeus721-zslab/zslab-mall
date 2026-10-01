package com.zslab.mall.inbox.stream;

import com.zslab.mall.inbox.enums.InboxAudience;
import java.util.Set;

/**
 * 한 커밋(또는 트랜잭션 밖 호출 1회)이 알릴 수신 범위. 내용 없이 "바뀌었다"만 알리므로 범위만 담는다.
 *
 * @param admins     관리자 연결 전체
 * @param allSellers 셀러 연결 전체(발행 지점이 sellerId를 손에 쥐고 있지 않은 경우)
 * @param sellerIds  지정 셀러의 연결만
 */
record InboxSignal(boolean admins, boolean allSellers, Set<Long> sellerIds) {

    boolean reaches(InboxAudience audience, Long sellerId) {
        return switch (audience) {
            case ADMIN -> admins;
            case SELLER -> allSellers || sellerIds.contains(sellerId);
        };
    }
}
