import type { CategorySummary } from '~/types/category'

/** layouts/default.vue → LayoutShell. 스킨이 layoutHeader를 선언했을 때만 채워진다(FE-69·useAppHeader). classic 셸은 쓰지 않는다. */
export interface LayoutShellVm {
  searchKeyword: string
  handleSearchSubmit: () => Promise<void>
  categoryMenuItems: CategorySummary[]
  accountMenuItems: { to: string; label: string }[]
  handleLogout: () => Promise<void>
  isBuyerSignedIn: boolean
  cartCount: number
  /** 현재 경로가 상품 목록·카테고리 화면(목록 카테고리 탭이 있는 화면)인지. 셸이 <768 헤더 카테고리 줄 중복을 숨길 때 쓴다(FE-82). */
  hasListingTabs: boolean
}
