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
}
