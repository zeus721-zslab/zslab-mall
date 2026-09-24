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
}
