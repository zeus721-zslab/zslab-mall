import type { Address, CreateAddressRequest, UpdateAddressRequest } from '~/types/address'

/**
 * 본인 배송지(주소록) 조회·CRUD·기본설정 composable(FE-13·useProfile 관습 복제). 전부 /api/v1/users/me/addresses 계열·
 * BUYER 인증 필요라 구매자 래퍼(useBuyerApi · 구매자 쿠키 인증)로 호출한다. 래퍼는 setup 시점에 만든다(이벤트 핸들러 뮤테이션 대비·useCheckout 관습).
 * 실패(RFC7807)는 $fetch가 throw하므로 호출부(page)가 처리하며, 목록은 page에서 useAsyncData로 감싸 SSR 로드한다.
 */
export function useAddresses() {
  const api = useBuyerApi()

  /** 배송지 목록(GET /api/v1/users/me/addresses → List&lt;AddressResponse&gt;). */
  function listAddresses(): Promise<Address[]> {
    return api<Address[]>('/v1/users/me/addresses')
  }

  /** 배송지 생성(POST /api/v1/users/me/addresses → 201 AddressResponse). 첫 주소는 서버가 기본으로 강제. */
  function createAddress(request: CreateAddressRequest): Promise<Address> {
    return api<Address>('/v1/users/me/addresses', {
      method: 'POST',
      body: request,
    })
  }

  /** 배송지 수정(PATCH /api/v1/users/me/addresses/{id} → AddressResponse·isDefault 제외). */
  function updateAddress(addressId: number, request: UpdateAddressRequest): Promise<Address> {
    // 템플릿 리터럴 경로는 nitro 타입드 라우트 추론이 과도해(TS2321) string으로 고정한다(useSellerApi 호출부 선례).
    const path: string = `/v1/users/me/addresses/${addressId}`
    return api<Address>(path, {
      method: 'PATCH',
      body: request,
    })
  }

  /** 배송지 삭제(DELETE /api/v1/users/me/addresses/{id} → 204·soft). */
  function removeAddress(addressId: number): Promise<void> {
    const path: string = `/v1/users/me/addresses/${addressId}`
    return api<void>(path, {
      method: 'DELETE',
    })
  }

  /** 기본 배송지 설정(PATCH /api/v1/users/me/addresses/{id}/default → 204·demote-then-set은 서버 책임). */
  function setDefaultAddress(addressId: number): Promise<void> {
    const path: string = `/v1/users/me/addresses/${addressId}/default`
    return api<void>(path, {
      method: 'PATCH',
    })
  }

  return { listAddresses, createAddress, updateAddress, removeAddress, setDefaultAddress }
}
