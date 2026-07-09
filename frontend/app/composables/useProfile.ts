import type { ChangePasswordRequest, Profile, UpdateProfileRequest } from '~/types/user'

/**
 * 본인 계정(프로필·비밀번호) 조회·수정 composable(FE-13). 모두 /api/v1/users/me 계열·BUYER 인증 필요라 Authorization: Bearer를
 * 주입한다(useOrders 관습 복제). config·auth는 setup 시점에 캡처한다(이벤트 핸들러에서 호출되는 뮤테이션 대비·useCheckout 관습).
 * 실패(RFC7807)는 $fetch가 throw하므로 호출부(page)가 처리하며, 조회는 page에서 useAsyncData로 감싸 SSR 로드한다.
 */
export function useProfile() {
  const config = useRuntimeConfig()
  const auth = useAuthStore()

  /** API base 이원화(useOrders·useCheckout와 동일): SSR은 내부 직결, 브라우저는 동일 Origin 상대경로. */
  function baseUrl(): string {
    return import.meta.server ? `${config.apiInternalBase}/api` : config.public.apiBase || '/api'
  }

  /** 프로필 조회(GET /api/v1/users/me → ProfileResponse). */
  function fetchProfile(): Promise<Profile> {
    return $fetch<Profile>('/v1/users/me', {
      baseURL: baseUrl(),
      headers: { Authorization: `Bearer ${auth.token}` },
    })
  }

  /** 프로필 수정(PATCH /api/v1/users/me body{name,phone} → 수정 후 ProfileResponse). */
  function updateProfile(request: UpdateProfileRequest): Promise<Profile> {
    return $fetch<Profile>('/v1/users/me', {
      baseURL: baseUrl(),
      method: 'PATCH',
      headers: { Authorization: `Bearer ${auth.token}` },
      body: request,
    })
  }

  /** 비밀번호 변경(PATCH /api/v1/users/me/password body{currentPassword,newPassword} → 204). */
  function changePassword(request: ChangePasswordRequest): Promise<void> {
    return $fetch<void>('/v1/users/me/password', {
      baseURL: baseUrl(),
      method: 'PATCH',
      headers: { Authorization: `Bearer ${auth.token}` },
      body: request,
    })
  }

  /** 회원 탈퇴(POST /api/v1/users/me/withdraw → 204·soft·멱등). 세션 정리·홈 이동은 호출부(page) 책임. */
  function withdraw(): Promise<void> {
    return $fetch<void>('/v1/users/me/withdraw', {
      baseURL: baseUrl(),
      method: 'POST',
      headers: { Authorization: `Bearer ${auth.token}` },
    })
  }

  return { fetchProfile, updateProfile, changePassword, withdraw }
}
