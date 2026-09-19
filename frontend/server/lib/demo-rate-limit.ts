/**
 * 데모 로그인 대행 라우트(/_demo/login·/_admin-demo/login) rate limit 코어(FE-43 검토 A 반영). h3·nitropack을 import하지 않는
 * 순수 함수라 vitest에서 직접 검증한다. 데모 라우트는 인증 없이 실제 JWT(특히 ADMIN)를 발급하므로 무제한 발급을 막는다.
 *
 * 고정 윈도우(슬라이딩 아님): 키별 첫 호출 시각부터 60초 동안 30회까지 허용하고, 윈도우가 지나면 카운터를 새로 시작한다.
 * 저장소는 모듈 스코프 Map — 단일 인스턴스 전제. 다중 인스턴스(수평 확장)로 전환하면 Redis 등 공유 저장소로 이관한다(이월).
 * 만료 엔트리는 consume 접근 시 정리한다(별도 타이머 없음 → 서버리스·테스트 환경에서 핸들 누수 없음).
 *
 * 현 구성에서 키는 gateway_nginx 컨테이너 IP(소켓 remoteAddress·FE-43a)라 라우트별 전역 버킷이다. 남용 억제가 목적이므로 의도대로
 * 동작하며, 한도 30회/분은 동시 방문자를 고려한 값(FE-43b). 클라이언트별 제한이 필요해지면 nginx X-Real-IP 참조로 전환한다(이월).
 */

export const RATE_LIMIT_WINDOW_MS = 60_000
export const RATE_LIMIT_MAX_ATTEMPTS = 30

interface WindowEntry {
  windowStartedAt: number
  count: number
}

export interface RateLimitDecision {
  allowed: boolean
  /** 거절 시 Retry-After 헤더 값(초·최소 1). 허용 시 0. */
  retryAfterSec: number
}

const buckets = new Map<string, WindowEntry>()

function isExpired(entry: WindowEntry, now: number): boolean {
  return now - entry.windowStartedAt >= RATE_LIMIT_WINDOW_MS
}

/** 키 1회 소비. 허용이면 카운터를 올리고, 한도 초과면 카운터를 올리지 않은 채 남은 윈도우 시간을 돌려준다. */
export function consume(key: string, now: number): RateLimitDecision {
  for (const [bucketKey, entry] of buckets) {
    if (isExpired(entry, now)) buckets.delete(bucketKey)
  }

  const entry = buckets.get(key)
  if (!entry) {
    buckets.set(key, { windowStartedAt: now, count: 1 })
    return { allowed: true, retryAfterSec: 0 }
  }
  if (entry.count < RATE_LIMIT_MAX_ATTEMPTS) {
    entry.count += 1
    return { allowed: true, retryAfterSec: 0 }
  }
  const remainingMs = entry.windowStartedAt + RATE_LIMIT_WINDOW_MS - now
  return { allowed: false, retryAfterSec: Math.max(1, Math.ceil(remainingMs / 1000)) }
}
