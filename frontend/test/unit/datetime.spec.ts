import { describe, it, expect } from 'vitest'
import { formatDateTime, toKstLocalDateTime } from '~/lib/utils/datetime'

describe('formatDateTime', () => {
  it('오프셋·초·밀리초를 무시하고 yyyy.MM.dd HH:mm으로 변환한다', () => {
    expect(formatDateTime('2026-07-10T14:30:00.000+09:00')).toBe('2026.07.10 14:30')
  })

  it('패턴 불일치 문자열은 원본을 폴백 반환한다', () => {
    expect(formatDateTime('invalid')).toBe('invalid')
  })

  it('빈 문자열은 빈 문자열을 반환한다', () => {
    expect(formatDateTime('')).toBe('')
  })

  it('오프셋 없이 T HH:mm까지만 있어도 매치해 변환한다', () => {
    expect(formatDateTime('2026-07-10T14:30')).toBe('2026.07.10 14:30')
  })
})

// FE-27 보강 2: 모의 결제 콜백 occurredAt은 BE LocalDateTime(KST 벽시계)이어야 paid_at이 주문일시와 같은 기준으로 저장된다.
describe('toKstLocalDateTime', () => {
  it('UTC 순간을 KST 벽시계(오프셋 없음·밀리초 3자리)로 변환한다', () => {
    expect(toKstLocalDateTime(new Date('2026-09-16T13:41:09.141Z'))).toBe('2026-09-16T22:41:09.141')
  })

  it('UTC 자정 직전은 KST 다음날 09시로 넘어간다(날짜 경계)', () => {
    expect(toKstLocalDateTime(new Date('2026-09-15T23:30:00.000Z'))).toBe('2026-09-16T08:30:00.000')
  })

  it('KST 주문일시 이후에 결제한 순간은 KST 표기로도 주문일시 이후다(BE orderedAt 비교 규칙)', () => {
    const orderedAtKst = '2026-09-15T19:41:22.348' // BE가 KST로 저장·직렬화한 주문일시
    const paidAt = toKstLocalDateTime(new Date('2026-09-15T10:41:24.745Z')) // 주문 2초 뒤 결제(UTC 순간)
    expect(paidAt).toBe('2026-09-15T19:41:24.745')
    expect(paidAt > orderedAtKst).toBe(true)
  })
})
