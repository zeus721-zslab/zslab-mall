import type { Address, CreateAddressRequest } from '~/types/address'

/**
 * 체크아웃 배송지 폼 순수 로직(FE-16). 저장 주소 선택·수기 입력 겸용 폼에서 "변경 여부 판정"과
 * "저장 요청 본문 생성"을 페이지(뷰)와 분리해 단위 테스트 가능하게 한다. deliveryMemo·addressLabel은
 * 주소록 저장 대상이 아니므로(스냅샷/별칭 전용) 여기서 다루지 않는다.
 */

/** 배송지 저장 비교·생성에 쓰는 공유 6필드(user_address == order_shipping_snapshot 공통). */
export interface CheckoutAddressForm {
  recipientName: string
  recipientPhone: string
  zonecode: string
  addressRoad: string
  addressJibun: string
  addressDetail: string
}

const SHARED_FIELDS: readonly (keyof CheckoutAddressForm)[] = [
  'recipientName',
  'recipientPhone',
  'zonecode',
  'addressRoad',
  'addressJibun',
  'addressDetail',
]

/**
 * 선택된 저장 주소와 현재 폼(공유 6필드)이 trim 기준으로 동일한지 판정한다.
 * selected가 null(=새 주소 입력)이면 "변경됨"으로 보고 false를 반환한다(항상 저장 후보).
 * 주소의 선택 필드(addressJibun·addressDetail)가 undefined면 빈 문자열과 동일 취급한다.
 */
export function isUnchanged(selected: Address | null, form: CheckoutAddressForm): boolean {
  if (!selected) return false
  return SHARED_FIELDS.every((field) => form[field].trim() === (selected[field] ?? '').trim())
}

/**
 * 체크아웃 폼(공유 6필드)에서 배송지 생성 요청 본문을 만든다(POST /users/me/addresses).
 * isDefault는 false 고정(체크아웃에서 기본 지정 미요청·첫 주소는 서버가 기본 강제).
 * addressLabel·deliveryMemo는 제외하고, 선택 필드는 trim 후 빈 값이면 undefined로 생략한다.
 */
export function buildCreateAddressRequest(form: CheckoutAddressForm): CreateAddressRequest {
  return {
    isDefault: false,
    recipientName: form.recipientName.trim(),
    recipientPhone: form.recipientPhone.trim(),
    zonecode: form.zonecode.trim(),
    addressRoad: form.addressRoad.trim(),
    addressJibun: form.addressJibun.trim() || undefined,
    addressDetail: form.addressDetail.trim() || undefined,
  }
}
