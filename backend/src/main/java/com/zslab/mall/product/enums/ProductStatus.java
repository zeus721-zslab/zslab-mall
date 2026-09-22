package com.zslab.mall.product.enums;

/**
 * 상품 상태(A분류·A#6·7값). DDL {@code product.status} ENUM 정합.
 *
 * <p><b>재결제 재검증(Track 4)</b>: D-51·D-60에서 {@code != SALE} 판정에 사용한다.
 *
 * <p><b>승인 전이(Track 50)</b>: 등록은 PENDING으로 생성되고 운영자 승인 시 SALE, 거부 시 REJECTED로 전이한다. 전이 강제는
 * {@link #canTransitionTo}로 하며(Product mutator가 이를 가드로 사용·SettlementStatus 동일 패턴), REJECTED는 종료 상태다
 * (재심사 없음).
 *
 * <p><b>판매 상태 전환(Track 71·96-5)</b>: 운영자·셀러가 SALE ↔ STOPPED(판매중지·재판매)를 전환한다. 중지 주체는
 * {@link SaleStopSource}로 기록되며 관리자 중지(ADMIN)는 셀러가 재판매할 수 없다(D-206·주체 가드는 Service). HIDDEN·DRAFT·APPROVED에서의
 * 전이는 여전히 소비처가 없어 도입하지 않는다.
 */
public enum ProductStatus {
    DRAFT,
    PENDING,
    APPROVED,
    REJECTED,
    SALE,
    HIDDEN,
    STOPPED;

    /**
     * 현재 상태에서 {@code next}로의 전이가 합법인지 판정한다(Track 50 승인 워크플로·Track 71 판매 상태 전환).
     *
     * @param next 목표 상태
     * @return 합법 전이면 true(PENDING→SALE·PENDING→REJECTED·SALE→STOPPED·STOPPED→SALE만·그 외 전부 false)
     */
    public boolean canTransitionTo(ProductStatus next) {
        return switch (this) {
            case PENDING -> next == SALE || next == REJECTED;
            case SALE -> next == STOPPED;
            case STOPPED -> next == SALE;
            // 그 외 상태(DRAFT·APPROVED·REJECTED·HIDDEN)에서의 전이는 범위 밖 — 전부 불가
            default -> false;
        };
    }
}
