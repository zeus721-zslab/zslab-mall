import { describe, it, expect } from 'vitest'
import { resolvePaymentRedirect } from '~/lib/payment-redirect'
import { MOCK_PG_ORIGIN } from '~/lib/constants/payment'

// Track 97 D-209: 결제 시작 redirectUrl 분기 — Mock PG origin은 내부 /payment/mock, 그 외는 외부 이동.
const LOCATION = '/api/v1/orders/ord_01ABC'

describe('resolvePaymentRedirect', () => {
  it('Mock PG origin → 내부 /payment/mock 경로(attemptKey·amount·method·orderPublicId 유지)', () => {
    const redirectUrl = `${MOCK_PG_ORIGIN}/checkout?attemptKey=pat_01XYZ&amount=15000&method=CARD`
    expect(resolvePaymentRedirect(redirectUrl, LOCATION)).toEqual({
      kind: 'mock',
      path: '/payment/mock?attemptKey=pat_01XYZ&amount=15000&method=CARD&orderPublicId=ord_01ABC',
    })
  })

  it('Mock PG origin + Location 없음 → orderPublicId 빈 문자열(기존 동작)', () => {
    const redirectUrl = `${MOCK_PG_ORIGIN}/checkout?attemptKey=pat_01XYZ&amount=15000&method=CARD`
    expect(resolvePaymentRedirect(redirectUrl, null)).toEqual({
      kind: 'mock',
      path: '/payment/mock?attemptKey=pat_01XYZ&amount=15000&method=CARD&orderPublicId=',
    })
  })

  it('외부 origin → URL 그대로 외부 이동', () => {
    const redirectUrl = 'https://pay.example-pg.com/checkout/session_123?token=abc'
    expect(resolvePaymentRedirect(redirectUrl, LOCATION)).toEqual({ kind: 'external', url: redirectUrl })
  })

  it('Mock 호스트라도 스킴·포트가 다르면 origin 불일치 → 외부 이동', () => {
    expect(resolvePaymentRedirect('http://mock-pg.zslab.local/checkout?attemptKey=pat_1', LOCATION).kind).toBe('external')
    expect(resolvePaymentRedirect('https://mock-pg.zslab.local:8443/checkout?attemptKey=pat_1', LOCATION).kind).toBe('external')
  })

  it('URL로 파싱되지 않는 redirectUrl → TypeError 전파(기존 new URL 동작 유지)', () => {
    expect(() => resolvePaymentRedirect('not-a-url', LOCATION)).toThrow(TypeError)
    expect(() => resolvePaymentRedirect('', LOCATION)).toThrow(TypeError)
  })
})
