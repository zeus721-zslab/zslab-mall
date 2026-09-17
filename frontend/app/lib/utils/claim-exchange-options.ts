import type { ProductVariant } from '~/types/product'

/**
 * 교환 옵션 후보 계산(FE-30-1 α·순수 함수). 상품 상세의 variant 목록(BE는 SALE variant만 내림)에서 BE 규칙(D-177 결정 9)과 같은 기준으로
 * 거른다: 같은 판매가(주문 단가 = basePrice + additionalPrice·BE ClaimExchangeService.validateExchangeOption과 동일 기준)·품절 아님·
 * 현재 옵션 아님. 최종 보장은 BE(422)이며 FE 필터는 선택지를 줄여 실패를 미리 막는 용도다.
 */
export interface ExchangeOptionCandidate {
  variantPublicId: string
  label: string
  salePrice: number
}

/** BE OptionLabelResolver와 같은 형식("그룹: 값"을 " / "로 연결)의 옵션 라벨. 옵션 없는 단순상품은 빈 문자열. */
export function variantOptionLabel(variant: Pick<ProductVariant, 'options'>): string {
  return variant.options.map((option) => `${option.groupName}: ${option.value}`).join(' / ')
}

export function exchangeOptionCandidates(
  variants: ProductVariant[],
  currentVariantPublicId: string,
  orderUnitPrice: number,
): ExchangeOptionCandidate[] {
  return variants
    .filter((variant) => variant.variantPublicId !== currentVariantPublicId)
    .filter((variant) => variant.salePrice === orderUnitPrice)
    .filter((variant) => !variant.soldOut)
    .map((variant) => ({
      variantPublicId: variant.variantPublicId,
      label: variantOptionLabel(variant) || '기본 옵션',
      salePrice: variant.salePrice,
    }))
}
