import { describe, it, expect } from 'vitest'
import { resolveCasePathRedirect } from '~~/server/utils/case-path-redirect'

// FE-92: 서버 routeRules·쿠키 path·경로 분기는 대소문자를 구분하므로 첫 세그먼트만 소문자로 301 보낸다.
describe('resolveCasePathRedirect', () => {
  it('/Admin/login → /admin/login', () => {
    expect(resolveCasePathRedirect('GET', '/Admin/login', '')).toBe('/admin/login')
  })

  it('/SELLER/orders?page=2 → 쿼리 원문 유지', () => {
    expect(resolveCasePathRedirect('GET', '/SELLER/orders', '?page=2')).toBe('/seller/orders?page=2')
  })

  it('첫 세그먼트가 소문자면 뒤 세그먼트 대문자가 있어도 null', () => {
    expect(resolveCasePathRedirect('GET', '/mypage/Orders/ABC123', '')).toBeNull()
  })

  it('/Mypage/orders/ABC123 → 뒤 세그먼트 보존', () => {
    expect(resolveCasePathRedirect('GET', '/Mypage/orders/ABC123', '')).toBe('/mypage/orders/ABC123')
  })

  it('HEAD도 정규화', () => {
    expect(resolveCasePathRedirect('HEAD', '/Admin/login', '')).toBe('/admin/login')
  })

  it('POST는 null', () => {
    expect(resolveCasePathRedirect('POST', '/Admin/login', '')).toBeNull()
  })

  it('루트 / → null', () => {
    expect(resolveCasePathRedirect('GET', '/', '')).toBeNull()
  })

  it('첫 세그먼트가 비어 있으면 null(//Evil.com)', () => {
    expect(resolveCasePathRedirect('GET', '//Evil.com', '')).toBeNull()
  })

  it('첫 세그먼트가 한글 인코딩(대문자 hex)이면 null', () => {
    expect(resolveCasePathRedirect('GET', '/%ED%95%9C%EA%B8%80/Page', '')).toBeNull()
  })

  it('첫 세그먼트가 \\로 시작하면 null(//외부 호스트 해석 방지)', () => {
    expect(resolveCasePathRedirect('GET', '/\\Evil.com', '')).toBeNull()
  })
})
