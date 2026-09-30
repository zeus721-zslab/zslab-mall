import type { ProductSectionId } from '~/lib/constants/product-sections'

/** 섹션 바 알약 하나(뷰가 화면에 있는 섹션만 만든다 · 라벨은 건수 포함). */
export interface ProductSectionNavItem {
  id: ProductSectionId
  label: string
}

/**
 * 상품 상세 한눈에 칩·진행형 섹션 바 vm(Track 106-2 · ProductDetailPageVm.sectionNav). 스크롤 관찰·진행률 계산은 페이지 composable이 하고
 * 뷰는 결과만 그린다. progress는 섹션 id → 0~1(읽은 비율). stickyTop = 바가 붙을 위치(헤더 높이 px · 상단 안전 영역은 뷰가 더한다).
 */
export interface ProductSectionNavVm {
  activeId: ProductSectionId | null
  progress: Partial<Record<ProductSectionId, number>>
  stickyTop: number
  /** 섹션으로 부드럽게 이동(동작 줄이기 설정이면 즉시). */
  go: (id: ProductSectionId) => void
  /** 묻기 섹션으로 이동하고 입력창에 포커스(스크롤이 튀지 않게 preventScroll). */
  ask: () => void
}
