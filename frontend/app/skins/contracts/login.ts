/** pages/login.vue → LoginView. email·password는 뷰의 입력이 v-model로 쓴다. */
export interface LoginPageVm {
  passwordChangedNotice: boolean
  /** 비밀번호 재설정 완료 후 재로그인 안내 표시(D-269). */
  passwordResetNotice: boolean
  /** 비밀번호 재설정 사용 가능(실 메일 발송 켜짐·D-269). false면 "비밀번호 찾기"는 준비 중 안내만 띄운다(FE-81). */
  passwordResetEnabled: boolean
  email: string
  password: string
  submitting: boolean
  errorMessage: string
  demoEnabled: boolean
  handleSubmit: () => Promise<void>
  handleDemoLogin: () => Promise<void>
  /** 회원가입 링크. 받은 redirect가 있으면 그대로 붙인다(FE-74). */
  signupLink: string
}
