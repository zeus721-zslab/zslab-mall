import { describe, it, expect } from 'vitest'
import { cartQuantityErrorMessage } from '~/lib/constants/cart'

// P-08: 담기·수량 변경 실패 중 수량 사유만 전용 문구로 바꾼다(상품 상세·장바구니 공용). 그 외는 null → 호출부 일반 문구.
describe('cartQuantityErrorMessage', () => {
  it('400 VALIDATION_FAILED(quantity) → 단건 상한 문구', () => {
    expect(cartQuantityErrorMessage({
      statusCode: 400,
      data: { code: 'VALIDATION_FAILED', fieldErrors: [{ field: 'quantity' }] },
    })).toBe('수량은 최대 999개까지 선택할 수 있습니다.')
  })

  it('422 CART_ITEM_QUANTITY_LIMIT_EXCEEDED → 합산 상한 문구', () => {
    expect(cartQuantityErrorMessage({ statusCode: 422, data: { code: 'CART_ITEM_QUANTITY_LIMIT_EXCEEDED' } }))
      .toBe('장바구니에 담긴 수량과 합쳐 최대 999개까지 담을 수 있습니다.')
  })

  it('수량과 무관한 실패(400 다른 필드·422 다른 코드·500) → null', () => {
    expect(cartQuantityErrorMessage({
      statusCode: 400,
      data: { code: 'VALIDATION_FAILED', fieldErrors: [{ field: 'variantPublicId' }] },
    })).toBeNull()
    expect(cartQuantityErrorMessage({ statusCode: 422, data: { code: 'CART_ITEM_NOT_PURCHASABLE' } })).toBeNull()
    expect(cartQuantityErrorMessage({ statusCode: 500 })).toBeNull()
  })
})
