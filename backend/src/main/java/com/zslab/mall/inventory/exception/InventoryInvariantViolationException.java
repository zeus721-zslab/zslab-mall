package com.zslab.mall.inventory.exception;

/**
 * 재고 불변조건(INV-1·INV-3·INV-4) 위반 시 도메인 계층에서 발생한다(D-101 §2·§6). 전역적으로 422로 매핑된다
 * ({@code GlobalExceptionHandler}·{@code RefundInvariantViolationException} 선례 정합).
 *
 * <p>대표 케이스:
 * <ul>
 *   <li>INV-1: 예약 결과 quantity_available &lt; 0 (oversell 차단·reserve)</li>
 *   <li>INV-3: quantity_reserved &lt; 0 (예약 없는 해제·차감·release·commitReservation)</li>
 *   <li>INV-4: quantity_on_hand &lt; 0 (실물 부족 차감·commitReservation)</li>
 * </ul>
 */
public class InventoryInvariantViolationException extends RuntimeException {

    public InventoryInvariantViolationException(String message) {
        super(message);
    }
}
