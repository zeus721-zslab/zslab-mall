/** pages/signup.vue → SignupView. 입력 상한 상수는 페이지가 넘긴다(lib/constants/account 이름 그대로). */
export interface SignupPageVm {
  email: string
  name: string
  phone: string
  password: string
  submitting: boolean
  errorMessage: string
  handleSubmit: () => Promise<void>
  EMAIL_MAX: number
  NAME_MAX: number
  PHONE_MAX: number
  PASSWORD_MIN: number
  PASSWORD_MAX: number
}
