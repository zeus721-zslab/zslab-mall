import { IRREVERSIBLE, riskConfirmMessage } from '~/lib/utils/risk-confirm'

/**
 * 계정 폼 검증 상한 단일 소스(FE-13·BE Bean Validation @Size 미러·매직넘버 방지). 값은 BE DTO(SoT)와 정확히 일치한다.
 * SoT: SignupRequest·UpdateProfileRequest·ChangePasswordRequest·CreateAddressRequest·UpdateAddressRequest.
 * BE는 형식 검증만·도메인 규칙은 서버가 최종 판단하므로 FE 상한은 UX(입력 절단·조기 안내) 용도다.
 */

// 회원(user) — SoT: SignupRequest·UpdateProfileRequest·ChangePasswordRequest
export const EMAIL_MAX = 254 // SoT: SignupRequest.email @Size(max=254)
export const NAME_MAX = 50
export const PHONE_MAX = 20
export const PASSWORD_MIN = 8
export const PASSWORD_MAX = 72

// 배송지(user_address) — SoT: CreateAddressRequest·UpdateAddressRequest
export const RECIPIENT_NAME_MAX = 50
export const RECIPIENT_PHONE_MAX = 20
export const ADDRESS_LABEL_MAX = 50
export const ZONECODE_MAX = 10
export const ADDRESS_ROAD_MAX = 200
export const ADDRESS_JIBUN_MAX = 200
export const ADDRESS_DETAIL_MAX = 200
// D-230 데모 계정 보호 — SoT: BE GlobalExceptionHandler DEMO_ACCOUNT_PROTECTED(403)·DemoAccountGuard 문구.
export const DEMO_ACCOUNT_PROTECTED_CODE = 'DEMO_ACCOUNT_PROTECTED'
export const DEMO_ACCOUNT_PROTECTED_MESSAGE = '데모 계정은 이 기능을 사용할 수 없습니다.'

/** 요청 오류가 데모 계정 보호(403)면 안내 문구, 아니면 null(비밀번호 변경·탈퇴 화면 공용). */
export function demoAccountProtectedMessage(error: unknown): string | null {
  const code = (error as { data?: { code?: unknown } } | null)?.data?.code
  return code === DEMO_ACCOUNT_PROTECTED_CODE ? DEMO_ACCOUNT_PROTECTED_MESSAGE : null
}

// 휴대폰 형식 — SoT: UpdateProfileRequest @Pattern(AdminMemberUpdateRequest.PHONE_PATTERN·Track 84·D-178). 국내 휴대폰·하이픈 선택.
export const PHONE_PATTERN = /^01[016789]-?\d{3,4}-?\d{4}$/

/**
 * 회원 탈퇴 확인 문구(Track 102 FE-64 위험 조작 문구 규약 — 무엇이 일어나는지 + 가역성).
 */
export const WITHDRAW_NOTICE = riskConfirmMessage(
  '계정이 비활성화되고 다시 로그인할 수 없습니다.',
  IRREVERSIBLE,
)
