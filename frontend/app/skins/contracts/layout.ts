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
  /**
   * 현재 경로가 화면 자체 탭 줄을 가진 화면인지 — 상품 목록·카테고리(목록 카테고리 탭 · FE-82)와 마이페이지 틀 화면(메뉴 칩 줄 · Track 105-4g-3).
   * 셸이 <768 헤더 카테고리 줄 중복을 숨길 때 쓴다.
   */
  hasPageTabRow: boolean
}
