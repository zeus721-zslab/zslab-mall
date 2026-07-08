import type { ProductListResponse } from '~/types/product'

/** 홈 신상품 섹션 노출 건수(고정). */
const HOME_PRODUCT_LIMIT = 8

/**
 * 홈 신상품 목록 조회(GET /api/v1/products?sort=LATEST&size=8).
 *
 * API base 이원화 흡수(FE-02 §1-A 3): SSR은 apiInternalBase(도커 내부 직결·/api 미포함)에 "/api"를 부가하고,
 * 브라우저는 동일 Origin 상대경로 "/api"를 쓴다(런타임 public.apiBase 미설정 시 폴백). 양쪽 모두 최종 .../api/v1/products로 귀결.
 * useFetch 반환(data·pending·error·refresh)을 그대로 노출한다.
 */
export function useProducts() {
  const config = useRuntimeConfig()
  const baseUrl = import.meta.server
    ? `${config.apiInternalBase}/api`
    : config.public.apiBase || '/api'

  return useFetch<ProductListResponse>('/v1/products', {
    key: 'home-products-latest',
    baseURL: baseUrl,
    query: { sort: 'LATEST', size: HOME_PRODUCT_LIMIT },
  })
}
