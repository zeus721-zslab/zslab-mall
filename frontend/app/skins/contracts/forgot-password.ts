/** pages/forgot-password.vue → ForgotPasswordView(D-269). requested면 입력 폼 대신 같은 안내 문구만 보인다(가입 여부 비노출). */
export interface ForgotPasswordPageVm {
  /** 메일 발송 비활성(BE availability false) — 폼 대신 준비 중 안내. */
  unavailable: boolean
  email: string
  submitting: boolean
  requested: boolean
  errorMessage: string
  handleSubmit: () => Promise<void>
  EMAIL_MAX: number
}
