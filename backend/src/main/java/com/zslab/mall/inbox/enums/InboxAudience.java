package com.zslab.mall.inbox.enums;

/**
 * 인박스를 보는 역할(D-248). 유형마다 어느 인박스에 들어가는지를 정한다. 셀러 인박스는 요청 셀러 소유 항목만 수집한다.
 */
public enum InboxAudience {
    ADMIN,
    SELLER
}
