import { describe, it, expect } from 'vitest'
import { consume, RATE_LIMIT_MAX_ATTEMPTS, RATE_LIMIT_WINDOW_MS } from '~~/server/lib/demo-rate-limit'

// 저장소가 모듈 스코프 Map이라 테스트 간 상태가 공유된다 → 테스트마다 고유 키를 써서 격리한다(reset 헬퍼 미도입).
const T0 = 1_000_000

describe('demo-rate-limit 코어(고정 윈도우 60s·키당 10회)', () => {
  it('10회 통과 → 11회차 거절·Retry-After는 남은 윈도우(초·올림)', () => {
    const key = 'demo-login:10.0.0.1'
    for (let attempt = 1; attempt <= RATE_LIMIT_MAX_ATTEMPTS; attempt++) {
      expect(consume(key, T0 + attempt * 100)).toEqual({ allowed: true, retryAfterSec: 0 })
    }
    const denied = consume(key, T0 + 1_500)
    expect(denied.allowed).toBe(false)
    expect(denied.retryAfterSec).toBe(Math.ceil((RATE_LIMIT_WINDOW_MS - 1_500) / 1000))
    // 거절은 카운터를 올리지 않지만 윈도우 안에서는 계속 거절된다.
    expect(consume(key, T0 + 59_000).allowed).toBe(false)
  })

  it('윈도우 경과 후 재통과(카운터 새로 시작)', () => {
    const key = 'demo-login:10.0.0.2'
    for (let attempt = 0; attempt < RATE_LIMIT_MAX_ATTEMPTS; attempt++) consume(key, T0)
    expect(consume(key, T0 + RATE_LIMIT_WINDOW_MS - 1).allowed).toBe(false)
    expect(consume(key, T0 + RATE_LIMIT_WINDOW_MS)).toEqual({ allowed: true, retryAfterSec: 0 })
    // 새 윈도우에서도 다시 10회까지만 허용된다.
    for (let attempt = 1; attempt < RATE_LIMIT_MAX_ATTEMPTS; attempt++) consume(key, T0 + RATE_LIMIT_WINDOW_MS + attempt)
    expect(consume(key, T0 + RATE_LIMIT_WINDOW_MS + 100).allowed).toBe(false)
  })

  it('키 분리: IP가 다르면 독립 · 라우트 식별자가 다르면 독립', () => {
    for (let attempt = 0; attempt < RATE_LIMIT_MAX_ATTEMPTS; attempt++) consume('demo-login:10.0.0.3', T0)
    expect(consume('demo-login:10.0.0.3', T0).allowed).toBe(false)
    expect(consume('demo-login:10.0.0.4', T0).allowed).toBe(true)
    expect(consume('admin-demo-login:10.0.0.3', T0).allowed).toBe(true)
  })

  it('거절 직전 최소 Retry-After는 1초(윈도우 끝 직전 올림)', () => {
    const key = 'demo-login:10.0.0.5'
    for (let attempt = 0; attempt < RATE_LIMIT_MAX_ATTEMPTS; attempt++) consume(key, T0)
    expect(consume(key, T0 + RATE_LIMIT_WINDOW_MS - 1).retryAfterSec).toBe(1)
  })
})
