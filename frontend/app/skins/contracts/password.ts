/** pages/mypage/password.vue → PasswordView. temporaryNotice는 임시 비밀번호 로그인 안내 표시 여부. */
export interface PasswordPageVm {
  temporaryNotice: boolean
  currentPassword: string
  newPassword: string
  newPasswordConfirm: string
  submitting: boolean
  errorMessage: string
  handleSubmit: () => Promise<void>
  PASSWORD_MIN: number
  PASSWORD_MAX: number
}
