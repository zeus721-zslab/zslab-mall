import { PASSWORD_CHANGE_PATH, PASSWORD_CHANGE_REASON_QUERY, PASSWORD_CHANGE_REASON_TEMPORARY } from '~/lib/constants/auth'

/**
 * 비밀번호 변경 강제 리다이렉트 판정(Track 84·순수 함수·vitest 대상). 로그인 중이고 강제 상태면 비밀번호 변경 페이지·로그인 페이지 외 이동을
 * 변경 페이지(사유 query 부착)로 돌린다. 관리자 영역(/admin/**)은 별도 세션(admin_token)이라 판정하지 않는다.
 * 로그아웃은 라우트가 아니라 헤더 액션이라 허용 경로에 없고, 로그아웃 시 상태 쿠키가 지워져 판정 자체가 해제된다.
 */
export interface PasswordChangeGuardInput {
  path: string
  authenticated: boolean
  required: boolean
}

// 도움말(Track 102 FE-64)은 공개 경로이고, 임시 비밀번호 상태에서 무엇을 해야 하는지 찾아볼 수 있어야 해서 함께 허용한다.
const ALLOWED_PATHS = new Set<string>([PASSWORD_CHANGE_PATH, '/login', '/help'])

/** 리다이렉트 대상 경로(query 포함) 또는 null(통과). */
export function resolvePasswordChangeRedirect(input: PasswordChangeGuardInput): string | null {
  if (!input.required || !input.authenticated) return null
  // 관리자(/admin·admin_token)·셀러(/seller·seller_token) 영역은 세션이 독립이므로 buyer 세션 상태가 셀러 영역을 간섭하면 안 된다(Track 90-A D-4).
  if (input.path.startsWith('/admin') || input.path.startsWith('/seller')) return null
  if (ALLOWED_PATHS.has(input.path)) return null
  return `${PASSWORD_CHANGE_PATH}?${PASSWORD_CHANGE_REASON_QUERY}=${PASSWORD_CHANGE_REASON_TEMPORARY}`
}
