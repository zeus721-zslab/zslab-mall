package com.zslab.mall.inventory.policy;

/**
 * 재고 임박 구간(가용재고 {@value #MIN}~{@value #MAX}) 단일 소스(Track 89-A).
 *
 * <p>대시보드 "재고 임박" 카운트({@code AdminDashboardRepository.countLowStock})와 관리자 상품 목록 재고 필터
 * ({@code AdminProductSpecifications.stockFilter})가 같은 구간을 써야 대시보드 타일 → 상품 목록 링크가 정합하므로
 * 두 패키지가 모두 의존하는 inventory 패키지에 둔다(product → dashboard 역방향 의존 회피).
 */
public final class LowStockThreshold {

    public static final int MIN = 1;
    public static final int MAX = 5;

    private LowStockThreshold() {
    }
}
