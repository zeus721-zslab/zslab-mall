package com.zslab.mall.delivery.policy;

/**
 * 장기 배송중 판정 기준(발송 후 {@value #DAYS}일 경과) 단일 소스(Track 99 D-210).
 *
 * <p>관리자·셀러 대시보드의 "장기 배송중" 카운트가 같은 기준을 써야 두 화면이 어긋나지 않으므로 delivery 패키지에 둔다
 * (dashboard → delivery 단방향 의존·{@code LowStockThreshold} 선례).
 *
 * <p><b>프런트 {@code frontend/app/lib/utils/elapsed-days.ts}의 {@code ELAPSED_WARNING_DAYS}와 같은 값이어야 한다</b> —
 * 대시보드 카운트와 목록 행의 "경과 N일" 주의 배지가 같은 건을 가리키게 하려는 것이다. 한쪽만 바꾸면 "대시보드에는 3건인데
 * 목록에서 주의 표시는 5건" 같은 어긋남이 생긴다.
 */
public final class LongShippingThreshold {

    /** 장기 배송중 기준 일수. FE elapsed-days.ts ELAPSED_WARNING_DAYS와 동일해야 한다. */
    public static final int DAYS = 3;

    private LongShippingThreshold() {
    }
}
