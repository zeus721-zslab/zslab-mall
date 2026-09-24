/** pages/login.vue → LoginView. email·password는 뷰의 입력이 v-model로 쓴다. */
export interface LoginPageVm {
  passwordChangedNotice: boolean
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
