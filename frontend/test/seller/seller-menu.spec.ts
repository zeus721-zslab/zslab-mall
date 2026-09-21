import { describe, it, expect } from 'vitest'
import { SELLER_MENU, resolveActiveSellerMenuPath } from '#layers/seller/app/lib/constants/seller-menu'

// 메뉴 구조(대시보드/주문/상품/통계/정산/설정)와 활성 판정. 90-B-3 대시보드·주문·배송·정산 + 90-C-3 상품·재고 + 90-D-1 클레임 + 90-D-2 비밀번호 변경 + 90-D-3 정산계좌(설정 그룹) + 90-E-1~3 통계 매출·주문클레임·상품이 경로를 가진다(비활성 항목 0).
describe('SELLER_MENU', () => {
  it('순서 고정 · 대시보드·주문·배송·클레임·상품·재고·통계 매출·주문클레임·상품·정산·비밀번호 변경·정산계좌 전부 경로 보유 · 비활성 0', () => {
    expect(SELLER_MENU.map((group) => group.label)).toEqual(['대시보드', '주문', '상품', '통계', '정산', '설정'])
    const paths = SELLER_MENU.flatMap((group) => [group.to, ...(group.children ?? []).map((child) => child.to)]).filter(Boolean)
    expect(paths).toEqual(['/seller', '/seller/orders', '/seller/deliveries', '/seller/claims', '/seller/products', '/seller/products/inventory', '/seller/stats/sales', '/seller/stats/orders', '/seller/stats/products', '/seller/settlements', '/seller/settings/password', '/seller/settings/bank-account'])
    const statsChildren = SELLER_MENU.find((group) => group.label === '통계')?.children ?? []
    expect(statsChildren.map((child) => [child.label, child.to ?? null])).toEqual([['매출', '/seller/stats/sales'], ['주문·클레임', '/seller/stats/orders'], ['상품', '/seller/stats/products']])
  })

  it('활성 판정: /seller 정확 일치 · 설정·통계 하위 경로 정확 일치 · 메뉴 밖(/seller/login) null', () => {
    expect(resolveActiveSellerMenuPath('/seller')).toBe('/seller')
    expect(resolveActiveSellerMenuPath('/seller/stats/sales')).toBe('/seller/stats/sales')
    expect(resolveActiveSellerMenuPath('/seller/stats/orders')).toBe('/seller/stats/orders')
    expect(resolveActiveSellerMenuPath('/seller/stats/products')).toBe('/seller/stats/products')
    expect(resolveActiveSellerMenuPath('/seller/settings/password')).toBe('/seller/settings/password')
    expect(resolveActiveSellerMenuPath('/seller/settings/bank-account')).toBe('/seller/settings/bank-account')
    expect(resolveActiveSellerMenuPath('/seller/login')).toBeNull()
  })
})
