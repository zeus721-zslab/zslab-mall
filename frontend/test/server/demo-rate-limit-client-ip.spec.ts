import { describe, it, expect } from 'vitest'
import { createEvent, getRequestIP } from 'h3'
import type { IncomingMessage, ServerResponse } from 'node:http'
import { consume, RATE_LIMIT_MAX_ATTEMPTS } from '~~/server/lib/demo-rate-limit'

// FE-43a 회귀: 데모 라우트의 rate limit 키는 소켓 remoteAddress(gateway 연결 IP)만 신뢰한다.
// 운영 실측 — 클라이언트가 임의 X-Forwarded-For를 붙이면 nginx $proxy_add_x_forwarded_for가 "위조값, 실IP"로 append하고,
// getRequestIP(event, { xForwardedFor: true })는 첫 값(위조값)을 읽어 매 요청 새 버킷이 생겨 한도가 무력화됐다.
// 라우트(_demo/login.post.ts·_admin-demo/login.post.ts)와 동일하게 getRequestIP(event) ?? 'unknown'으로 키를 만든다.
const GATEWAY_IP = '172.18.0.5'
const T0 = 5_000_000

function fakeEvent(remoteAddress: string | undefined, forwardedFor?: string) {
  const req = {
    method: 'POST',
    url: '/_demo/login',
    headers: forwardedFor === undefined ? {} : { 'x-forwarded-for': forwardedFor },
    socket: { remoteAddress },
  } as unknown as IncomingMessage
  const res = {} as ServerResponse
  return createEvent(req, res)
}

function bucketKey(routeKey: string, remoteAddress: string | undefined, forwardedFor?: string): string {
  return `${routeKey}:${getRequestIP(fakeEvent(remoteAddress, forwardedFor)) ?? 'unknown'}`
}

describe('demo rate limit 클라이언트 IP 해소(FE-43a·XFF 불신)', () => {
  it('위조 XFF가 매 요청 달라도 버킷이 분리되지 않는다(소켓 IP 기준 단일 키)', () => {
    const keys = new Set<string>()
    for (let attempt = 0; attempt < 5; attempt++) {
      keys.add(bucketKey('demo-login', GATEWAY_IP, `203.0.113.${attempt}, ${GATEWAY_IP}`))
    }
    expect(keys.size).toBe(1)
    expect([...keys][0]).toBe(`demo-login:${GATEWAY_IP}`)
  })

  it('위조 XFF를 바꿔가며 호출해도 remoteAddress 기준으로 누적돼 11회차부터 거절', () => {
    for (let attempt = 0; attempt < RATE_LIMIT_MAX_ATTEMPTS; attempt++) {
      const key = bucketKey('demo-login-xff', GATEWAY_IP, `198.51.100.${attempt}`)
      expect(consume(key, T0 + attempt).allowed).toBe(true)
    }
    expect(consume(bucketKey('demo-login-xff', GATEWAY_IP, '198.51.100.250'), T0 + 100).allowed).toBe(false)
    expect(consume(bucketKey('demo-login-xff', GATEWAY_IP), T0 + 101).allowed).toBe(false)
  })

  it('대조: 옛 방식(xForwardedFor: true)은 첫 값(위조값)을 읽는다 — 우회의 원인', () => {
    const event = fakeEvent(GATEWAY_IP, `203.0.113.7, ${GATEWAY_IP}`)
    expect(getRequestIP(event, { xForwardedFor: true })).toBe('203.0.113.7')
    expect(getRequestIP(event)).toBe(GATEWAY_IP)
  })

  it("remoteAddress 미확보 시 'unknown' 단일 버킷(XFF가 있어도 승격하지 않음)", () => {
    expect(bucketKey('demo-login', undefined, '203.0.113.9')).toBe('demo-login:unknown')
  })
})
