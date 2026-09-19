/**
 * 셀러 사이드바 메뉴 단일 소스(Track 90-A). 순서는 확정 사양 고정(대시보드 → 주문 → 상품 → 통계 → 정산 → 설정).
 * `to`가 있는 항목만 pages/seller/** 와 1:1이며, 화면이 아직 없는 항목(90-B 이후)은 `to` 없이 비활성으로 표시한다 — 라우트를 미리 만들지 않는다.
 */
export interface SellerMenuItem {
  label: string
  /** 화면이 구현된 항목만 경로를 가진다. 없으면 사이드바가 비활성(disabled)으로 렌더한다. */
  to?: string
}

export interface SellerMenuGroup {
  label: string
  /** 그룹 자체가 단일 링크면 to, 하위 항목이 있으면 children. 둘 다 없으면 단일 항목이되 미구현(비활성). */
  to?: string
  children?: SellerMenuItem[]
}

export const SELLER_MENU: SellerMenuGroup[] = [
  { label: '대시보드', to: '/seller' },
  {
    label: '주문',
    children: [{ label: '주문' }, { label: '배송' }, { label: '클레임' }],
  },
  {
    label: '상품',
    children: [{ label: '상품' }, { label: '재고' }],
  },
  {
    label: '통계',
    children: [{ label: '매출' }, { label: '주문·클레임' }, { label: '상품' }],
  },
  { label: '정산' },
  { label: '설정' },
]

/**
 * 현재 경로에 해당하는 메뉴 경로를 찾는다(관리자 FE-25 동형). 정확 일치를 우선하고, 없으면 "메뉴 경로 + '/'"로 시작하는 가장 긴 메뉴 경로를 택한다.
 * 메뉴 밖 경로(비밀번호 변경 안내 등)는 null. 사이드바 활성·상단바 브레드크럼이 함께 쓴다.
 */
export function resolveActiveSellerMenuPath(path: string): string | null {
  const menuPaths = SELLER_MENU.flatMap((group) =>
    group.to ? [group.to] : (group.children ?? []).flatMap((child) => (child.to ? [child.to] : [])),
  )
  if (menuPaths.includes(path)) return path
  const nested = menuPaths
    .filter((menuPath) => menuPath !== '/seller' && path.startsWith(`${menuPath}/`))
    .sort((a, b) => b.length - a.length)
  return nested[0] ?? null
}
