import type { ProductSort } from '~/types/product'

/**
 * 상품 목록 정렬 옵션 단일 소스(FE-05 products/index.vue 인라인 → FE-20 승격). value=ProductSort 유니온으로 타입 고정
 * (매직 문자열 방지), label=UI 표기. 소비처 = ProductSortSelect·ProductListView(URL 쿼리 복원 검증).
 */
export const PRODUCT_SORT_OPTIONS: { value: ProductSort; label: string }[] = [
  { value: 'LATEST', label: '최신순' },
  { value: 'PRICE_ASC', label: '가격 낮은순' },
  { value: 'PRICE_DESC', label: '가격 높은순' },
  { value: 'NAME', label: '이름순' },
]

/** 목록 기본 정렬(BE ProductCatalogController 기본값 LATEST 정합). */
export const DEFAULT_PRODUCT_SORT: ProductSort = 'LATEST'
