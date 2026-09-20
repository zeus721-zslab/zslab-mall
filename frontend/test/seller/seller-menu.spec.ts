import { describe, it, expect } from 'vitest'
import { SELLER_MENU, resolveActiveSellerMenuPath } from '#layers/seller/app/lib/constants/seller-menu'

// 메뉴 구조(대시보드/주문/상품/통계/정산/설정)와 활성 판정. 90-B-3 기준 대시보드·주문·배송·정산만 경로를 가지며 나머지는 비활성(to 없음·라우트 미생성).
describe('SELLER_MENU', () => {
  it('순서 고정 · 대시보드·주문·배송·정산만 경로 보유', () => {
    expect(SELLER_MENU.map((group) => group.label)).toEqual(['대시보드', '주문', '상품', '통계', '정산', '설정'])
    const paths = SELLER_MENU.flatMap((group) => [group.to, ...(group.children ?? []).map((child) => child.to)]).filter(Boolean)
    expect(paths).toEqual(['/seller', '/seller/orders', '/seller/deliveries', '/seller/settlements'])
  })

  it('활성 판정: /seller 정확 일치 · 메뉴 밖(/seller/settings/password) null', () => {
    expect(resolveActiveSellerMenuPath('/seller')).toBe('/seller')
    expect(resolveActiveSellerMenuPath('/seller/settings/password')).toBeNull()
    expect(resolveActiveSellerMenuPath('/seller/login')).toBeNull()
  })
})
