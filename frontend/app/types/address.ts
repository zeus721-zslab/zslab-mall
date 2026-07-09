/**
 * 회원 배송지(주소록) 요청·응답 타입(FE-13·BE UserAddress 도메인 DTO 대응). 주문 시점 스냅샷(OrderShippingSnapshot)과
 * 별개 테이블이다. 식별자 id는 내부 Long(소유권 스코프로 보호·public_id 미도입) → FE는 number로 미러.
 */

/** 배송지 조회·생성·수정 응답(BE AddressResponse 대응). opt 필드는 미입력 시 null 가능. */
export interface Address {
  id: number
  isDefault: boolean
  addressLabel?: string
  recipientName: string
  recipientPhone: string
  zonecode: string
  addressRoad: string
  addressJibun?: string
  addressDetail?: string
}

/** 배송지 생성 요청(BE CreateAddressRequest 대응). 첫 주소는 서버가 기본으로 강제(isDefault 요청값 무관). */
export interface CreateAddressRequest {
  isDefault: boolean
  addressLabel?: string
  recipientName: string
  recipientPhone: string
  zonecode: string
  addressRoad: string
  addressJibun?: string
  addressDetail?: string
}

/** 배송지 수정 요청(BE UpdateAddressRequest 대응). isDefault 제외(기본 전환은 PATCH .../{id}/default 별도 경로). */
export type UpdateAddressRequest = Omit<CreateAddressRequest, 'isDefault'>
