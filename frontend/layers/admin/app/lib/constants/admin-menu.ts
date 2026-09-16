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
      { to: '/admin/orders/payments', label: '결제 내역' },
      { to: '/admin/orders/cancellations', label: '취소' },
      { to: '/admin/orders/returns', label: '반품' },
      { to: '/admin/orders/exchanges', label: '교환' },
      { to: '/admin/orders/refunds', label: '환불' },
      { to: '/admin/orders/deliveries', label: '배송 관리' },
    ],
  },
  {
    label: '상품 관리',
    children: [
      { to: '/admin/products', label: '상품 목록' },
      { to: '/admin/products/new', label: '상품 등록' },
      { to: '/admin/products/categories', label: '카테고리' },
      { to: '/admin/products/inventory', label: '재고' },
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
      { to: '/admin/stats/products', label: '상품' },
    ],
  },
]
