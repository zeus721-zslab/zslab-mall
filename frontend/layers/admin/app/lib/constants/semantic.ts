/**
 * 관리자 의미 색상 단일 소스(FE-25 보강·부트스트랩 개념). 토스트·chip·버튼이 같은 4종을 쓰며, 색은 "요청 성공 여부"가 아니라
 * "결과의 의미"로 고른다: danger=부정(품절 ON·삭제·실패) / warning=경고(일부 실패·차단 안내) / success=긍정(재고 회복·전부 성공) /
 * info=중립(상태 전환 등 기본 알림).
 */
export type AdminSemantic = 'danger' | 'warning' | 'success' | 'info'

/** Vuetify 테마 색 키(lib/vuetify.ts theme: error #EF4444·warning #F97316·success #22C55E·info #0EA5E9). */
export const ADMIN_SEMANTIC_VUETIFY_COLOR: Record<AdminSemantic, 'error' | 'warning' | 'success' | 'info'> = {
  danger: 'error',
  warning: 'warning',
  success: 'success',
  info: 'info',
}

/** vue-sonner richColors variant(danger→error). */
export const ADMIN_SEMANTIC_TOAST_TYPE: Record<AdminSemantic, 'error' | 'warning' | 'success' | 'info'> = {
  danger: 'error',
  warning: 'warning',
  success: 'success',
  info: 'info',
}

/** 뱃지(chip) 연한 톤 CSS 클래스(admin-vuetify.css --adm-semantic-* 토큰). */
export function semanticChipClass(semantic: AdminSemantic): string {
  return `adm-chip adm-chip--${semantic}`
}
