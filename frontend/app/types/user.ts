/**
 * 계정(회원) 관련 요청·응답 타입(FE-13·BE user 도메인 DTO 대응). 식별자 publicId는 usr_ prefix 문자열.
 * email은 로그인 자격증명이라 프로필 수정 대상이 아니다(UpdateProfileRequest는 name·phone만).
 */

/** 회원가입 요청(BE SignupRequest 대응). 성공 시 SignupResponse{userPublicId}만 반환·토큰 미발급. */
export interface SignupRequest {
  email: string
  name: string
  phone: string
  password: string
}

/** 프로필 조회·수정 응답(BE ProfileResponse 대응). passwordHash 등 민감정보는 미노출. */
export interface Profile {
  publicId: string
  email: string
  name: string
  phone: string
}

/** 프로필 수정 요청(BE UpdateProfileRequest 대응). email 제외(자격증명·수정 불가). */
export interface UpdateProfileRequest {
  name: string
  phone: string
}

/** 비밀번호 변경 요청(BE ChangePasswordRequest 대응). 현재 비밀번호 확인 후 교체. */
export interface ChangePasswordRequest {
  currentPassword: string
  newPassword: string
}
