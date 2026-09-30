/**
 * 상품 상세 섹션 id 단일 소스(Track 106-2 · 진행형 섹션 바·한눈에 칩의 이동 대상). 페이지 composable은 이 id로 섹션을 찾고, 스킨 뷰는
 * 같은 id를 섹션 요소에 붙인다. 묻기 입력창 id는 칩·바에서 포커스를 옮길 때 쓴다.
 */
export const PRODUCT_SECTION_IDS = {
  description: 'product-description',
  reviews: 'product-reviews',
  questions: 'product-questions',
} as const

export type ProductSectionId = (typeof PRODUCT_SECTION_IDS)[keyof typeof PRODUCT_SECTION_IDS]

/** 섹션 바 요소 id(높이를 재어 스크롤 여백에 반영한다). */
export const PRODUCT_SECTION_NAV_ID = 'product-section-nav'

/** 묻기 섹션 입력창 id(칩의 "물어보기"가 포커스를 준다). */
export const PRODUCT_QUESTION_INPUT_ID = 'product-question-input'

/** 섹션 제목이 바에 가리지 않게 헤더 + 바 아래로 더 띄우는 여백(px). */
export const PRODUCT_SECTION_SCROLL_GAP = 16

/** 섹션 스크롤 여백 CSS 변수(헤더 + 바 + 여백 · 페이지 composable이 문서 루트에 둔다). */
export const PRODUCT_SECTION_OFFSET_VAR = '--product-section-offset'
