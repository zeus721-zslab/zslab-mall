import {
  PASSWORD_RESET_AVAILABILITY_PATH,
  PASSWORD_RESET_CONFIRM_PATH,
  PASSWORD_RESET_REQUEST_PATH,
} from '~/lib/constants/password-reset'

/**
 * 구매자 비밀번호 재설정 API(D-269). 모두 /api/v1/auth/** 공개 경로라 인증은 필요 없지만, 구매자 쿠키가 남아 있으면 BE가 CSRF를 요구하므로
 * CSRF 헤더를 싣는 구매자 래퍼(useBuyerApi)로 호출한다. 실패(RFC7807)는 그대로 throw해 호출부(page)가 처리한다.
 */
export function usePasswordReset() {
  const api = useBuyerApi()

  /** 사용 가능 여부(실 메일 발송이 켜져 있는지). */
  async function fetchAvailability(): Promise<boolean> {
    const response = await api<{ enabled: boolean }>(PASSWORD_RESET_AVAILABILITY_PATH)
    return response.enabled
  }

  /** 재설정 메일 요청(202·가입 여부와 무관하게 같은 응답). */
  function requestReset(email: string): Promise<void> {
    return api<void>(PASSWORD_RESET_REQUEST_PATH, { method: 'POST', body: { email } })
  }

  /** 재설정 확정(204 · 토큰 무효 400 PASSWORD_RESET_TOKEN_INVALID). */
  function confirmReset(token: string, newPassword: string): Promise<void> {
    return api<void>(PASSWORD_RESET_CONFIRM_PATH, { method: 'POST', body: { token, newPassword } })
  }

  return { fetchAvailability, requestReset, confirmReset }
}
