/** 품목 1건 수량 상한(장바구니·바로구매 공통). BE zslab.order.max-unpaid-quantity-per-variant(20)와 맞춘 값이다(FE-114). */
export const CART_ITEM_QUANTITY_MAX = 20

/** 상한을 넘기려 할 때·서버가 수량 형식 오류(400)를 돌려줄 때 공통 안내. */
export const CART_QUANTITY_LIMIT_NOTICE = `수량은 최대 ${CART_ITEM_QUANTITY_MAX}개까지 선택할 수 있습니다.`

/** 스테퍼 다음 값을 1~상한으로 보정한다(상한 초과 입력은 상한으로). */
export function clampCartQuantity(quantity: number): number {
  return Math.min(Math.max(quantity, 1), CART_ITEM_QUANTITY_MAX)
}

/** 담기·수량 변경 실패 응답(RFC7807 + BE fieldErrors) 중 수량 판정에 필요한 최소 형태. */
export interface CartQuantityErrorLike {
  statusCode?: number
  data?: { code?: string; fieldErrors?: { field?: string }[] }
}

/** 같은 옵션을 다시 담아 장바구니 합산이 상한을 넘을 때(422 CART_ITEM_QUANTITY_LIMIT_EXCEEDED) 안내. 단건 수량은 정상이라 문구를 나눈다. */
export const CART_QUANTITY_SUM_LIMIT_NOTICE = `장바구니에 담긴 수량과 합쳐 최대 ${CART_ITEM_QUANTITY_MAX}개까지 담을 수 있습니다.`

/** 400 VALIDATION_FAILED 중 quantity 필드 오류인지(BE @Min(1)·@Max(999) 위반). */
function isQuantityValidationError(error: CartQuantityErrorLike): boolean {
  if (error.statusCode !== 400 || error.data?.code !== 'VALIDATION_FAILED') return false
  const fieldErrors = error.data.fieldErrors
  return Array.isArray(fieldErrors) && fieldErrors.some((entry) => entry.field === 'quantity')
}

/**
 * 담기·수량 변경 실패 중 수량 사유의 안내 문구(P-08 · 상품 상세·장바구니 공용). 단건 상한 위반(400)·합산 상한 초과(422)만 문구를 주고,
 * 그 외 실패는 null — 호출부가 자기 일반 문구를 쓴다.
 */
export function cartQuantityErrorMessage(error: CartQuantityErrorLike): string | null {
  if (isQuantityValidationError(error)) return CART_QUANTITY_LIMIT_NOTICE
  if (error.statusCode === 422 && error.data?.code === 'CART_ITEM_QUANTITY_LIMIT_EXCEEDED') return CART_QUANTITY_SUM_LIMIT_NOTICE
  return null
}
