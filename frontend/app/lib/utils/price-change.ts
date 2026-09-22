/**
 * 상품 판매가 변경 확인 판정 단일 소스(Track 99 FE-61·관리자·셀러 공용). 수정 저장은 확인 절차 없이 바로 반영되는데 판매가는
 * 판매중 상품에도 즉시 적용되므로(오타 1자리가 곧 손실), 저장 직전에 "전 → 후 (변동률)"을 한 번 보여 주고 확인을 받는다.
 * 가격이 그대로면 확인하지 않는다(null).
 */

/** 이 비율(%) 이상 움직이면 강조 문구를 함께 보인다. 절대값 기준이라 인상·인하 모두 대상. */
export const PRICE_CHANGE_EMPHASIS_PERCENT = 50

export interface PriceChange {
  before: number
  after: number
  /** 변동률(%·소수 첫째 자리 반올림). 원래 가격이 0이면 비율을 낼 수 없어 null. */
  ratePercent: number | null
  /** 절대 변동률이 {@link PRICE_CHANGE_EMPHASIS_PERCENT} 이상인가. 비율을 낼 수 없으면 false. */
  emphasized: boolean
}

/**
 * 판매가 변동. 값이 같으면 null(확인 불필요). 폼 값은 미입력이 null일 수 있는데, 그 경우도 확인 대상이 아니다
 * (저장 전 검증이 이미 막는다).
 */
export function priceChangeOf(before: number | null, after: number | null): PriceChange | null {
  if (before === null || after === null || before === after) return null
  // 원래 가격 0은 비율의 분모가 될 수 없다(무한대) — 금액만 보여 주고 강조는 하지 않는다.
  const ratePercent = before === 0 ? null : Math.round(((after - before) / before) * 1000) / 10
  return {
    before,
    after,
    ratePercent,
    emphasized: ratePercent !== null && Math.abs(ratePercent) >= PRICE_CHANGE_EMPHASIS_PERCENT,
  }
}

/** 확인 다이얼로그 본문 1줄. 비율을 낼 수 없으면 금액만 적는다. */
export function priceChangeMessage(change: PriceChange): string {
  const amounts = `${change.before.toLocaleString('ko-KR')}원 → ${change.after.toLocaleString('ko-KR')}원`
  if (change.ratePercent === null) return `판매가를 ${amounts}로 바꿉니다.`
  const sign = change.ratePercent > 0 ? '+' : ''
  return `판매가를 ${amounts} (${sign}${change.ratePercent}%)로 바꿉니다.`
}

/** 강조 문구(변동폭이 큰 경우만). 강조 대상이 아니면 빈 배열. */
export function priceChangeWarnings(change: PriceChange): string[] {
  if (!change.emphasized) return []
  return [`변동폭이 ${PRICE_CHANGE_EMPHASIS_PERCENT}% 이상입니다. 자릿수를 다시 확인하세요.`]
}
