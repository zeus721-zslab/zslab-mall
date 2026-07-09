/**
 * 장바구니 조회 응답(GET /api/v1/cart). 이번 단계는 뱃지 카운트(items.length)만 소비하므로
 * 요소 shape는 타이핑하지 않는다(장바구니 페이지 트랙으로 이연).
 */
export interface CartResponse {
  items: unknown[]
}
