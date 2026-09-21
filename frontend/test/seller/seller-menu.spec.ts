import { describe, it, expect } from 'vitest'
import { SELLER_MENU, resolveActiveSellerMenuPath } from '#layers/seller/app/lib/constants/seller-menu'

// 메뉴 구조(대시보드/주문/상품/통계/정산/설정)와 활성 판정. 90-B-3 대시보드·주문·배송·정산 + 90-C-3 상품·재고 + 90-D-1 클레임 + 90-D-2 비밀번호 변경 + 90-D-3 정산계좌(설정 그룹)가 경로를 가지며 통계는 비활성(to 없음·라우트 미생성).
describe('SELLER_MENU', () => {
  it('순서 고정 · 대시보드·주문·배송·클레임·상품·재고·정산·비밀번호 변경·정산계좌만 경로 보유', () => {
    expect(SELLER_MENU.map((group) => group.label)).toEqual(['대시보드', '주문', '상품', '통계', '정산', '설정'])
    const paths = SELLER_MENU.flatMap((group) => [group.to, ...(group.children ?? []).map((child) => child.to)]).filter(Boolean)
    expect(paths).toEqual(['/seller', '/seller/orders', '/seller/deliveries', '/seller/claims', '/seller/products', '/seller/products/inventory', '/seller/settlements', '/seller/settings/password', '/seller/settings/bank-account'])
  })

  it('활성 판정: /seller 정확 일치 · 설정 하위 경로 정확 일치 · 메뉴 밖(/seller/login) null', () => {
    expect(resolveActiveSellerMenuPath('/seller')).toBe('/seller')
    expect(resolveActiveSellerMenuPath('/seller/settings/password')).toBe('/seller/settings/password')
    expect(resolveActiveSellerMenuPath('/seller/settings/bank-account')).toBe('/seller/settings/bank-account')
    expect(resolveActiveSellerMenuPath('/seller/login')).toBeNull()
  })
})
