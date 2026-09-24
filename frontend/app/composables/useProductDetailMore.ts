import type { ProductListResponse, ProductSummary } from '~/types/product'

// 같은 셀러 상품을 이만큼 조회해 현재 상품을 빼고 최대 표시 개수만 남긴다(목록 API에 제외 파라미터가 없음·recon track105-2d §1-6).
const SELLER_PRODUCTS_FETCH_SIZE = 6
const SELLER_PRODUCTS_MAX = 5

/**
 * 상품 상세의 "셀러의 다른 상품"(FE-70·renew 상세). 페이지가 스킨이 productDetailMore를 선언했을 때만 호출한다.
 * 상세 응답의 sellerPublicId로 셀러 필터 목록을 조회한다(D-221). SSR에서도 상세 조회가 끝난 뒤 조회하도록 상세 요청을 기다린다.
 * 조회 실패·결과 0개는 빈 배열이다(뷰가 섹션을 숨김·페이지 에러 아님).
 */
export function useProductDetailMore(productPublicId: string, productDetail: ReturnType<typeof useProductDetail>) {
  const config = useRuntimeConfig()
  const baseURL = import.meta.server
    ? `${config.apiInternalBase}/api`
    : config.public.apiBase || '/api'

  const sellerPublicId = computed<string | null>(() => productDetail.data.value?.sellerPublicId ?? null)

  const { data, error } = useAsyncData(
    `product-detail-more:${productPublicId}`,
    async (): Promise<ProductSummary[]> => {
      await productDetail
      if (sellerPublicId.value === null) return []
      const response = await $fetch<ProductListResponse>('/v1/products', {
        baseURL,
        query: { sort: 'LATEST', size: SELLER_PRODUCTS_FETCH_SIZE, sellerPublicId: sellerPublicId.value },
      })
      return response.items
        .filter((item) => item.productPublicId !== productPublicId)
        .slice(0, SELLER_PRODUCTS_MAX)
    },
    // 셀러가 정해지면(페이지 내 이동의 상세 도착·재시도 성공) 조회한다. 브라우저에서 상세가 아직 없으면 첫 조회를 건너뛴다 —
    // 첫 조회가 상세를 기다리는 사이 watch가 한 번 더 조회해 같은 요청이 두 번 나가지 않도록. 서버는 watch가 없어 바로 조회한다.
    { immediate: import.meta.server || sellerPublicId.value !== null, watch: [sellerPublicId] },
  )

  return {
    sellerProducts: computed<ProductSummary[]>(() => (error.value ? [] : data.value ?? [])),
  }
}
