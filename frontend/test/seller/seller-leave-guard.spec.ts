import { describe, it, expect } from 'vitest'
import { shouldReloadOnSellerLeave } from '#layers/seller/app/lib/seller-leave-guard'

// 이탈 가드 판정(관리자 D-15 동형): 셀러 밖 경로(사용자·관리자 포함)만 전체 새로고침 대상. 셀러 내부 이동(로그아웃 → /seller/login 포함)은 대상 아님.
describe('shouldReloadOnSellerLeave', () => {
  it.each(['/', '/products', '/login', '/admin', '/admin/login', '/sellers', '/seller-x'])('셀러 밖 %s → true', (path) => {
    expect(shouldReloadOnSellerLeave(path)).toBe(true)
  })

  it.each(['/seller', '/seller/login', '/seller/settings/password', '/seller/login?redirect=%2Fseller'])('셀러 내부 %s → false', (path) => {
    expect(shouldReloadOnSellerLeave(path)).toBe(false)
  })
})
