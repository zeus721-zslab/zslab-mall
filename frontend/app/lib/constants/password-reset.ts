/**
 * 구매자 비밀번호 재설정 단일 소스(D-269 · FE-112). API 경로·화면 경로·안내 문구·BE 오류 코드.
 * SoT: BE PasswordResetController(/api/v1/auth/password-reset/*) · PasswordResetService.RESET_PAGE_PATH("/reset-password?token=")
 * · GlobalExceptionHandler PASSWORD_RESET_TOKEN_INVALID(400).
 */

/** API(useBuyerApi base `/api` 기준 상대 경로). availability(GET) = { enabled } · request(POST) = 202 · confirm(POST) = 204. */
export const PASSWORD_RESET_AVAILABILITY_PATH = '/v1/auth/password-reset/availability'
export const PASSWORD_RESET_REQUEST_PATH = '/v1/auth/password-reset/request'
export const PASSWORD_RESET_CONFIRM_PATH = '/v1/auth/password-reset/confirm'

/** 화면 경로. 재설정 화면은 메일 링크(BE가 만든 절대 URL)의 경로와 같아야 한다. */
export const FORGOT_PASSWORD_PAGE = '/forgot-password'
export const RESET_PASSWORD_PAGE = '/reset-password'
export const RESET_PASSWORD_TOKEN_QUERY = 'token'

/** 로그인 화면 재설정 완료 안내 query 값(LOGIN_NOTICE_QUERY와 함께 쓴다). */
export const LOGIN_NOTICE_PASSWORD_RESET = 'password-reset'

/** 재설정 링크 유효 시간(분) — SoT: BE PasswordResetService.TOKEN_TTL_MINUTES. 안내 문구에만 쓴다(만료 판정은 BE). */
export const PASSWORD_RESET_TTL_MINUTES = 30

/** BE 400 — 없음·만료·사용됨을 구분하지 않는 단일 코드. */
export const PASSWORD_RESET_TOKEN_INVALID_CODE = 'PASSWORD_RESET_TOKEN_INVALID'

export const PASSWORD_RESET_MESSAGES = {
  /** 메일 발송 비활성(EMAIL_SENDER=mock) — 로그인 화면 안내(FE-81 문구 유지). */
  unavailable: '비밀번호 찾기는 준비 중입니다.',
  /** 요청 후 — 가입 여부와 무관하게 같은 문구(이메일 열거 방지). */
  requested: `입력하신 이메일이 가입된 구매자 계정이면 비밀번호 재설정 링크를 보냈습니다. 메일함을 확인해 주세요. 링크는 ${PASSWORD_RESET_TTL_MINUTES}분 동안 유효합니다.`,
  requestFailed: '요청을 보내지 못했습니다. 잠시 후 다시 시도해 주세요.',
  /** 링크 무효(만료·사용됨·잘못된 링크) — 재요청 안내. */
  tokenInvalid: '재설정 링크가 만료되었거나 이미 사용되었습니다. 비밀번호 재설정을 다시 요청해 주세요.',
  tokenMissing: '재설정 링크가 올바르지 않습니다. 메일의 링크를 다시 확인하거나 재설정을 다시 요청해 주세요.',
  confirmMismatch: '새 비밀번호가 일치하지 않습니다',
  confirmFailed: '비밀번호를 변경하지 못했습니다. 입력값을 확인하거나 잠시 후 다시 시도해 주세요.',
  /** 로그인 화면 — 재설정 완료 후 안내. */
  completed: '비밀번호가 재설정되었습니다. 새 비밀번호로 로그인해 주세요.',
} as const

/** 요청 오류가 재설정 토큰 무효(400 PASSWORD_RESET_TOKEN_INVALID)면 true. */
export function isPasswordResetTokenInvalid(error: unknown): boolean {
  const code = (error as { data?: { code?: unknown } } | null)?.data?.code
  return code === PASSWORD_RESET_TOKEN_INVALID_CODE
}
