/**
 * 정산 상태·품목 유형 라벨 공용 단일 소스(Track 102 FE-64).
 *
 * 같은 값을 관리자 레이어(constants/admin-settlement.ts)와 셀러 레이어(constants/seller-settlement.ts)가 각자 라벨링해
 * PENDING이 "대기"와 "확정 전"으로 갈려 있었다. 라벨 문자열만 여기로 모으고 각 레이어는 기존 이름으로 re-export한다.
 */

/** BE SettlementStatus 3값(PENDING → CONFIRMED → PAID 직진). */
export type SettlementStatusCode = 'PENDING' | 'CONFIRMED' | 'PAID'

/**
 * PENDING은 확정 대기 — 관리자의 "대기"는 무엇을 기다리는지 말하지 않고, 셀러의 "확정 전"은 다음 상태 이름(확정·지급완료)과
 * 어미가 달랐다. 이 상태로 보내는 조작 이름도 "확정"으로 맞춘다(기존 "정상처리"는 상태 이름과 이어지지 않았다).
 */
export const SETTLEMENT_STATUS_LABELS: Record<SettlementStatusCode, string> = {
  PENDING: '확정 대기',
  CONFIRMED: '확정',
  PAID: '지급완료',
}

/**
 * BE SettlementItemType 3값(settlement_item.item_type ENUM·V37). 상세 품목 탭과 1:1.
 * CARRYOVER = 지급이 막힌 앞선 음수 정산의 부족분을 이 정산에서 차감한 행(Track 104-3b) — 헤더 "이월 차감"과 같은 이름을 쓴다.
 */
export type SettlementItemTypeCode = 'SALE' | 'REFUND' | 'CARRYOVER'

export const SETTLEMENT_ITEM_TYPE_LABELS: Record<SettlementItemTypeCode, string> = {
  SALE: '판매',
  REFUND: '환불',
  CARRYOVER: '이월 차감',
}
