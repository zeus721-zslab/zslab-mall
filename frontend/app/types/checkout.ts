/**
 * 체크아웃 요청/응답 타입(FE-11·BE CartCheckoutRequest·CheckoutResponse 대응). 장바구니 결제는 품목을 보내지 않고
 * (서버가 selected 조회) 배송지·결제수단만 전송한다. 응답은 payment(PG 발급·서버 canonical)와 next(재결제 안내)로 구성된다.
 */

/** 배송지(BE ShippingAddressRequest 대응). recipientName·recipientPhone·zonecode·addressRoad 필수, 나머지 선택. */
export interface ShippingAddress {
  recipientName: string
  recipientPhone: string
  zonecode: string
  addressRoad: string
  addressJibun?: string
  addressDetail?: string
  deliveryMemo?: string
}

/** 결제수단(BE PaymentMethod enum 대응). 매직 문자열은 lib/constants/payment.ts 단일 소스에서 파생. */
export type PaymentMethod = 'CARD' | 'BANK' | 'VBANK' | 'KAKAO'

/** 장바구니 체크아웃 요청(POST /api/v1/cart/checkout). items 없음(서버 selected 조회). */
export interface CheckoutRequest {
  shippingAddress: ShippingAddress
  method: PaymentMethod
}

/**
 * 체크아웃 응답(BE CheckoutResponse·NON_NULL 직렬화). 성공 시 payment.redirectUrl 존재·next 생략(null).
 * INITIATE_FAILED 시 payment.publicId=null·status.code='INITIATE_FAILED'·next.retryPaymentUrl 제공.
 */
export interface CheckoutResponse {
  payment: {
    publicId: string | null
    status: { code: string; label: string }
    redirectUrl: string | null
    expiresAt: string | null
  }
  next: { retryPaymentUrl?: string } | null
}
