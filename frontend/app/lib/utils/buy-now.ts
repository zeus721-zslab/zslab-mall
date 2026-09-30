import type { LocationQuery } from 'vue-router'
import type { ProductDetail } from '~/types/product'
import type { CheckoutSummaryItem } from '~/lib/utils/checkout-summary'

/**
 * 바로구매(FE-100 · D1 β): 상세 옵션 시트 → 주문서 단일 상품 경로. 상품·옵션·수량을 URL query로 넘겨 새로고침에도 유지하고,
 * 장바구니는 거치지 않는다(FE는 장바구니 API를 부르지 않는다 — 단, 결제가 완료되면 서버가 같은 variant의 장바구니 품목을 비운다 · D-126).
 * 제출은 POST /api/v1/orders이며 단가·판매 상태·재고·옵션 소속은 서버가 다시 검증한다(D-56).
 * 여기서는 query 형식만 거르고, 화면 금액은 공개 상품 상세로 만든 참고값이다(결제 금액은 서버 응답 기준).
 */
export interface BuyNowItem {
  productPublicId: string
  variantPublicId: string
  quantity: number
}

const BUY_NOW_QUERY = { product: 'product', variant: 'variant', quantity: 'quantity' } as const
const PRODUCT_PUBLIC_ID = /^prd_[0-9A-Z]{26}$/
const VARIANT_PUBLIC_ID = /^var_[0-9A-Z]{26}$/
const POSITIVE_INTEGER = /^[1-9][0-9]*$/
// 서버 수량 필드(OrderItemRequest.quantity · Java int)가 받는 최대값. 넘으면 역직렬화 400이라 형식 오류로 미리 거른다(업무 상한 아님).
const SERVER_QUANTITY_MAX = 2_147_483_647

export function buyNowCheckoutPath(item: BuyNowItem): string {
  const query = new URLSearchParams({
    [BUY_NOW_QUERY.product]: item.productPublicId,
    [BUY_NOW_QUERY.variant]: item.variantPublicId,
    [BUY_NOW_QUERY.quantity]: String(item.quantity),
  })
  return `/checkout?${query.toString()}`
}

/** 주문서가 바로구매 경로로 열렸는지(키가 하나라도 있으면 바로구매 — 형식이 틀려도 장바구니 결제로 새지 않게 한다). */
export function isBuyNowQuery(query: LocationQuery): boolean {
  return Object.values(BUY_NOW_QUERY).some((key) => key in query)
}

function singleValue(query: LocationQuery, key: string): string | null {
  const value = query[key]
  return typeof value === 'string' ? value : null
}

/** query → 바로구매 품목. 형식이 하나라도 틀리면 null(주문서가 안내 상태를 보여 준다). */
export function parseBuyNowQuery(query: LocationQuery): BuyNowItem | null {
  const productPublicId = singleValue(query, BUY_NOW_QUERY.product)
  const variantPublicId = singleValue(query, BUY_NOW_QUERY.variant)
  const quantityText = singleValue(query, BUY_NOW_QUERY.quantity)
  if (productPublicId === null || !PRODUCT_PUBLIC_ID.test(productPublicId)) return null
  if (variantPublicId === null || !VARIANT_PUBLIC_ID.test(variantPublicId)) return null
  if (quantityText === null || !POSITIVE_INTEGER.test(quantityText)) return null
  const quantity = Number(quantityText)
  if (quantity > SERVER_QUANTITY_MAX) return null
  return { productPublicId, variantPublicId, quantity }
}

/**
 * 상품 상세 + 바로구매 품목 → 주문서 요약 품목 1건. variant가 그 상품에 없으면 null.
 * 옵션 라벨은 장바구니 표기("그룹: 값 / 그룹: 값")와 같게 만든다.
 */
export function buildBuyNowSummaryItem(product: ProductDetail, item: BuyNowItem): CheckoutSummaryItem | null {
  const variant = product.variants.find((candidate) => candidate.variantPublicId === item.variantPublicId)
  if (!variant) return null
  const mainImage = product.images.find((image) => image.main) ?? product.images[0]
  const optionLabel = variant.options.map((option) => `${option.groupName}: ${option.value}`).join(' / ')
  return {
    variantPublicId: variant.variantPublicId,
    quantity: item.quantity,
    selected: true,
    productName: product.name,
    displayPrice: variant.salePrice,
    purchasable: !variant.soldOut && !product.soldOut && !product.saleStopped,
    thumbnailUrl: mainImage?.imageUrl ?? null,
    optionLabel: optionLabel === '' ? null : optionLabel,
  }
}
