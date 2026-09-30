import type { LocationQuery } from 'vue-router'
import type { CheckoutSummaryItem } from '~/lib/utils/checkout-summary'
import { buildBuyNowSummaryItem, parseBuyNowQuery, type BuyNowItem } from '~/lib/utils/buy-now'

/** 주문서 바로구매 경로의 품목 원천(FE-100). 장바구니 경로의 cart.load와 같은 자리를 채운다. */
export interface BuyNowCheckout {
  /** 형식이 맞는 바로구매 품목. 형식이 틀리면 null(조회하지 않고 빈 품목). */
  item: BuyNowItem | null
  /** 요약 품목(0~1건). 상품에 없는 variant면 빈 목록. */
  items: ComputedRef<CheckoutSummaryItem[]>
  error: ComputedRef<Error | undefined>
  refresh: () => Promise<void>
  /** 안내 링크용 상품 상세 경로(형식이 틀리면 상품 목록). */
  productPath: string
}

/**
 * 바로구매 주문서 품목(GET /api/v1/products/{prd_} 공개 상세로 표시용 품목을 만든다). 장바구니 API는 읽지도 바꾸지도 않는다
 * (결제 완료 시 서버의 같은 variant 장바구니 소진은 D-126 정책).
 * 표시 금액은 참고값이고 실제 단가·판매 상태·재고는 제출 시 서버가 검증한다(POST /api/v1/orders · D-56).
 */
export function useBuyNowCheckout(query: LocationQuery): BuyNowCheckout {
  const item = parseBuyNowQuery(query)
  if (!item) {
    return {
      item: null,
      items: computed(() => []),
      error: computed(() => undefined),
      refresh: async () => {},
      productPath: '/products',
    }
  }

  const { data, error, refresh } = useProductDetail(item.productPublicId)
  const items = computed<CheckoutSummaryItem[]>(() => {
    const summaryItem = data.value ? buildBuyNowSummaryItem(data.value, item) : null
    return summaryItem ? [summaryItem] : []
  })
  return {
    item,
    items,
    error: computed(() => error.value ?? undefined),
    refresh,
    productPath: `/products/${item.productPublicId}`,
  }
}
