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
