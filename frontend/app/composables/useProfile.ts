import type { ChangePasswordRequest, Profile, UpdateProfileRequest } from '~/types/user'

/**
 * 본인 계정(프로필·비밀번호) 조회·수정 composable(FE-13). 모두 /api/v1/users/me 계열·BUYER 인증 필요라 구매자 래퍼(useBuyerApi)로
 * 호출한다. 래퍼는 setup 시점에 만든다(이벤트 핸들러에서 호출되는 뮤테이션 대비·useCheckout 관습).
 * 실패(RFC7807)는 $fetch가 throw하므로 호출부(page)가 처리하며, 조회는 page에서 useAsyncData로 감싸 SSR 로드한다.
 */
export function useProfile() {
  const api = useBuyerApi()

  /** 프로필 조회(GET /api/v1/users/me → ProfileResponse). */
  function fetchProfile(): Promise<Profile> {
    return api<Profile>('/v1/users/me')
  }

  /** 프로필 수정(PATCH /api/v1/users/me body{name,phone} → 수정 후 ProfileResponse). */
  function updateProfile(request: UpdateProfileRequest): Promise<Profile> {
    return api<Profile>('/v1/users/me', {
      method: 'PATCH',
      body: request,
    })
  }

  /** 비밀번호 변경(PATCH /api/v1/users/me/password body{currentPassword,newPassword} → 204). */
  function changePassword(request: ChangePasswordRequest): Promise<void> {
    return api<void>('/v1/users/me/password', {
      method: 'PATCH',
      body: request,
    })
  }

  /** 회원 탈퇴(POST /api/v1/users/me/withdraw → 204·soft·멱등). 세션 정리·홈 이동은 호출부(page) 책임. */
  function withdraw(): Promise<void> {
    return api<void>('/v1/users/me/withdraw', {
      method: 'POST',
    })
  }

  return { fetchProfile, updateProfile, changePassword, withdraw }
}
