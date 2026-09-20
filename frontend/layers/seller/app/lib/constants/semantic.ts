/**
 * 셀러 의미 색상 단일 소스(Track 90-B-3·관리자 constants/semantic 복제·FE-44 §2 복제 원칙). 토스트·chip이 같은 4종을 쓰며,
 * 색은 "요청 성공 여부"가 아니라 "결과의 의미"로 고른다: danger=부정(실패·취소) / warning=경고(차단 안내·대기) / success=긍정(완료) / info=중립(상태 전환).
 */
export type SellerSemantic = 'danger' | 'warning' | 'success' | 'info'

/** vue-sonner richColors variant(danger→error). */
export const SELLER_SEMANTIC_TOAST_TYPE: Record<SellerSemantic, 'error' | 'warning' | 'success' | 'info'> = {
  danger: 'error',
  warning: 'warning',
  success: 'success',
  info: 'info',
}

/** 뱃지(chip) 연한 톤 CSS 클래스(seller-vuetify.css --slr-semantic-* 토큰). neutral은 0건·비교 불가(회색). */
export function semanticChipClass(semantic: SellerSemantic | 'neutral'): string {
  return `slr-chip slr-chip--${semantic}`
}
