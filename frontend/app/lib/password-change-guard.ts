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

const ALLOWED_PATHS = new Set<string>([PASSWORD_CHANGE_PATH, '/login'])

/** 리다이렉트 대상 경로(query 포함) 또는 null(통과). */
export function resolvePasswordChangeRedirect(input: PasswordChangeGuardInput): string | null {
  if (!input.required || !input.authenticated) return null
  if (input.path.startsWith('/admin')) return null
  if (ALLOWED_PATHS.has(input.path)) return null
  return `${PASSWORD_CHANGE_PATH}?${PASSWORD_CHANGE_REASON_QUERY}=${PASSWORD_CHANGE_REASON_TEMPORARY}`
}
