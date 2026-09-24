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
  /** 비밀번호 확인 입력. signupPasswordConfirm을 선언한 스킨만 검사한다(FE-74). BE로 보내지 않는다. */
  passwordConfirm: string
  /** 로그인 링크. 받은 redirect가 있으면 그대로 붙인다(FE-74). */
  loginLink: string
}
