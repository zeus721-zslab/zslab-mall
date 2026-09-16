import { describe, it, expect } from 'vitest'
import { shouldReloadOnLeave } from '#layers/admin/app/lib/admin-leave-guard'

// 이탈 가드 판정(FE-22c D-15): 관리자 밖 경로만 전체 새로고침 대상. 관리자 내부 이동(로그아웃 → /admin/login 포함)은 대상 아님.
describe('shouldReloadOnLeave', () => {
  it.each(['/', '/products', '/login', '/cart?x=1', '/administrator'])('관리자 밖 %s → true', (path) => {
    expect(shouldReloadOnLeave(path)).toBe(true)
  })

  it.each(['/admin', '/admin/login', '/admin/orders/payments', '/admin/login?redirect=%2Fadmin%2Forders'])('관리자 내부 %s → false', (path) => {
    expect(shouldReloadOnLeave(path)).toBe(false)
  })
})
