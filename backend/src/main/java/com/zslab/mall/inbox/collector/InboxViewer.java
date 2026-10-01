package com.zslab.mall.inbox.collector;

import com.zslab.mall.inbox.enums.InboxAudience;

/**
 * 인박스를 조회·보류하는 주체. 셀러는 {@code sellerId}로 소유 범위를 제한하고, 보류는 {@code userId}(구성원 개인) 단위로 적용한다.
 *
 * @param sellerId 셀러 인박스의 소속 셀러 id(관리자는 null)
 */
public record InboxViewer(InboxAudience audience, Long sellerId, Long userId) {

    public static InboxViewer admin(Long userId) {
        return new InboxViewer(InboxAudience.ADMIN, null, userId);
    }

    public static InboxViewer seller(Long sellerId, Long userId) {
        return new InboxViewer(InboxAudience.SELLER, sellerId, userId);
    }

    public boolean isSeller() {
        return audience == InboxAudience.SELLER;
    }
}
