/**
 * 관리자 사이드바 메뉴 단일 소스(FE-22). 순서는 확정 사양 고정(대시보드 → 회원 → 주문 → 상품 → 정산 → 통계).
 * 각 항목의 경로는 pages/admin/** 파일과 1:1이며, BE 조회 API가 아직 없는 화면은 공통 "준비 중" 플레이스홀더다.
 */
export interface AdminMenuItem {
  to: string
  label: string
}

export interface AdminMenuGroup {
  label: string
  /** 그룹 자체가 단일 링크면 to, 하위 항목이 있으면 children(둘 중 하나만). */
  to?: string
  children?: AdminMenuItem[]
}

export const ADMIN_MENU: AdminMenuGroup[] = [
  { label: '대시보드', to: '/admin' },
  {
    label: '회원 관리',
    children: [
      { to: '/admin/members', label: '일반회원' },
      { to: '/admin/members/sellers', label: '셀러' },
      { to: '/admin/members/admins', label: '관리자' },
      { to: '/admin/members/withdrawn', label: '탈퇴회원' },
    ],
  },
  {
    label: '주문 관리',
    children: [
      { to: '/admin/orders', label: '전체 주문' },
      { to: '/admin/orders/claims', label: '취소·반품·교환' },
      { to: '/admin/orders/deliveries', label: '배송 관리' },
      { to: '/admin/orders/reconciliation', label: '불일치' },
    ],
  },
  {
    label: '상품 관리',
    children: [
      { to: '/admin/products', label: '상품 목록' },
      { to: '/admin/products/new', label: '상품 등록' },
      { to: '/admin/products/categories', label: '카테고리' },
    ],
  },
  {
    label: '정산 관리',
    children: [
      { to: '/admin/settlements', label: '정산 내역' },
      { to: '/admin/settlements/sellers', label: '셀러별 정산' },
    ],
  },
  {
    label: '통계',
    children: [
      { to: '/admin/stats/sales', label: '매출' },
      { to: '/admin/stats/orders', label: '주문' },
      { to: '/admin/stats/members', label: '회원' },
    ],
  },
]

/**
 * 현재 경로에 해당하는 메뉴 경로를 찾는다(FE-25). 정확 일치를 우선하고, 없으면 "메뉴 경로 + '/'"로 시작하는 가장 긴 메뉴 경로를 택한다
 * (예: /admin/products/prd_… → /admin/products 상품 목록·/admin/products/new는 자체 메뉴가 정확 일치라 그대로).
 * 메뉴 밖 경로는 null. 사이드바 활성·상단바 브레드크럼이 함께 쓴다.
 */
export function resolveActiveMenuPath(path: string): string | null {
  const menuPaths = ADMIN_MENU.flatMap((group) => (group.to ? [group.to] : (group.children ?? []).map((child) => child.to)))
  if (menuPaths.includes(path)) return path
  const nested = menuPaths
    .filter((menuPath) => menuPath !== '/admin' && path.startsWith(`${menuPath}/`))
    .sort((a, b) => b.length - a.length)
  return nested[0] ?? null
}
