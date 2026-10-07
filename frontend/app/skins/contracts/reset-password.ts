/**
 * pages/reset-password.vue → ResetPasswordView(D-269). linkInvalid = 쿼리 토큰이 없거나 BE가 토큰 무효(만료·사용됨)로 거절한 상태 —
 * 폼 대신 재요청 안내를 보인다. 성공하면 페이지가 로그인 화면으로 이동한다.
 */
export interface ResetPasswordPageVm {
  newPassword: string
  newPasswordConfirm: string
  submitting: boolean
  linkInvalid: boolean
  linkInvalidMessage: string
  errorMessage: string
  handleSubmit: () => Promise<void>
  forgotPasswordLink: string
  PASSWORD_MIN: number
  PASSWORD_MAX: number
}
