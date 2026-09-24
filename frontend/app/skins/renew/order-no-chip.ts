/**
 * renew 주문번호 칩(Track 105-4g-3 · 한 곳). 식별자라 모노 + 연한 바탕 칩이다(주문 목록 카드의 기존 칩 그대로).
 * 값은 사람이 읽는 orderNo만 넣는다 — 내부 id(ord_…)는 링크·라우팅에만 쓰고 화면에 보이지 않는다.
 * 쓰는 곳: 주문 목록 카드 · 마이페이지 최근 주문 · 주문 상세 머리 · 주문 완료 · 클레임 상세 대상 품목.
 */
export const ORDER_NO_CHIP_CLASS =
  'inline-block whitespace-nowrap rounded-md bg-surface-muted px-2 py-0.5 font-mono text-caption font-normal text-sub'
