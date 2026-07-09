import type { ProductDetail } from '~/types/product'

/**
 * 구매자 상품 상세 조회(GET /api/v1/products/{productPublicId}). 홈 {@link useProducts}·목록
 * {@link useProductList}과 동일한 API base 이원화(SSR=apiInternalBase+/api·브라우저=public.apiBase||'/api')를
 * 적용한다. permitAll 공개 카탈로그라 인증 없이 조회 가능하다.
 *
 * 미존재·비노출 productPublicId는 백엔드가 404(PRODUCT_NOT_FOUND·존재 은닉)를 반환하며, useFetch가 error로
 * 노출하므로 페이지가 not-found 상태를 렌더한다. key는 productPublicId를 포함해 상품별로 캐시를 분리한다.
 */
export function useProductDetail(productPublicId: string) {
  const config = useRuntimeConfig()
  const baseUrl = import.meta.server
    ? `${config.apiInternalBase}/api`
    : config.public.apiBase || '/api'

  return useFetch<ProductDetail>(`/v1/products/${productPublicId}`, {
    key: `product-detail:${productPublicId}`,
    baseURL: baseUrl,
  })
}
