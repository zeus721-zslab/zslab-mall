import { PASSWORD_MAX, PASSWORD_MIN } from '~/lib/constants/account'

/**
 * 셀러 비밀번호 변경 폼 클라이언트 검증(Track 90-D-2·FE-50·순수 함수·vitest 대상). 길이 상·하한은 BE ChangePasswordRequest @Size(8~72)의
 * 미러인 사용자 상수(account.ts)를 그대로 쓴다. 확인 필드 일치는 BE가 받지 않는 필드라 여기서만 검증한다. 비밀번호는 trim하지 않는다(공백도 입력의 일부).
 */
export interface PasswordChangeFormInput {
  currentPassword: string
  newPassword: string
  newPasswordConfirm: string
}

export const PASSWORD_CHANGE_FORM_MESSAGES = {
  currentPasswordRequired: '현재 비밀번호를 입력하세요.',
  newPasswordLength: `새 비밀번호는 ${PASSWORD_MIN}자 이상 ${PASSWORD_MAX}자 이하여야 합니다.`,
  newPasswordConfirmMismatch: '새 비밀번호가 일치하지 않습니다.',
  /** BE 400 MALFORMED_REQUEST(현재 비밀번호 불일치·사유 은닉)를 현재 비밀번호 필드에 표시할 때 쓴다. */
  currentPasswordMismatch: '현재 비밀번호가 일치하지 않습니다.',
} as const

/** 필드별 첫 오류 메시지. 오류가 없으면 빈 객체(SellerInventoryAdjustDialog의 validate 관례). */
export function validatePasswordChangeForm(input: PasswordChangeFormInput): Record<string, string> {
  const errors: Record<string, string> = {}
  if (input.currentPassword === '') errors.currentPassword = PASSWORD_CHANGE_FORM_MESSAGES.currentPasswordRequired
  if (input.newPassword.length < PASSWORD_MIN || input.newPassword.length > PASSWORD_MAX) {
    errors.newPassword = PASSWORD_CHANGE_FORM_MESSAGES.newPasswordLength
  }
  if (input.newPasswordConfirm !== input.newPassword) errors.newPasswordConfirm = PASSWORD_CHANGE_FORM_MESSAGES.newPasswordConfirmMismatch
  return errors
}
