import type { Address, CreateAddressRequest, UpdateAddressRequest } from '~/types/address'

/**
 * 본인 배송지(주소록) 조회·CRUD·기본설정 composable(FE-13·useProfile 관습 복제). 전부 /api/v1/users/me/addresses 계열·
 * BUYER 인증 필요라 Authorization: Bearer를 주입한다. config·auth는 setup 시점에 캡처(이벤트 핸들러 뮤테이션 대비·useCheckout 관습).
 * 실패(RFC7807)는 $fetch가 throw하므로 호출부(page)가 처리하며, 목록은 page에서 useAsyncData로 감싸 SSR 로드한다.
 */
export function useAddresses() {
  const config = useRuntimeConfig()
  const auth = useAuthStore()

  /** API base 이원화(useProfile·useOrders와 동일): SSR은 내부 직결, 브라우저는 동일 Origin 상대경로. */
  function baseUrl(): string {
    return import.meta.server ? `${config.apiInternalBase}/api` : config.public.apiBase || '/api'
  }

  /** 배송지 목록(GET /api/v1/users/me/addresses → List&lt;AddressResponse&gt;). */
  function listAddresses(): Promise<Address[]> {
    return $fetch<Address[]>('/v1/users/me/addresses', {
      baseURL: baseUrl(),
      headers: { Authorization: `Bearer ${auth.token}` },
    })
  }

  /** 배송지 생성(POST /api/v1/users/me/addresses → 201 AddressResponse). 첫 주소는 서버가 기본으로 강제. */
  function createAddress(request: CreateAddressRequest): Promise<Address> {
    return $fetch<Address>('/v1/users/me/addresses', {
      baseURL: baseUrl(),
      method: 'POST',
      headers: { Authorization: `Bearer ${auth.token}` },
      body: request,
    })
  }

  /** 배송지 수정(PATCH /api/v1/users/me/addresses/{id} → AddressResponse·isDefault 제외). */
  function updateAddress(addressId: number, request: UpdateAddressRequest): Promise<Address> {
    return $fetch<Address>(`/v1/users/me/addresses/${addressId}`, {
      baseURL: baseUrl(),
      method: 'PATCH',
      headers: { Authorization: `Bearer ${auth.token}` },
      body: request,
    })
  }

  /** 배송지 삭제(DELETE /api/v1/users/me/addresses/{id} → 204·soft). */
  function removeAddress(addressId: number): Promise<void> {
    return $fetch<void>(`/v1/users/me/addresses/${addressId}`, {
      baseURL: baseUrl(),
      method: 'DELETE',
      headers: { Authorization: `Bearer ${auth.token}` },
    })
  }

  /** 기본 배송지 설정(PATCH /api/v1/users/me/addresses/{id}/default → 204·demote-then-set은 서버 책임). */
  function setDefaultAddress(addressId: number): Promise<void> {
    return $fetch<void>(`/v1/users/me/addresses/${addressId}/default`, {
      baseURL: baseUrl(),
      method: 'PATCH',
      headers: { Authorization: `Bearer ${auth.token}` },
    })
  }

  return { listAddresses, createAddress, updateAddress, removeAddress, setDefaultAddress }
}
