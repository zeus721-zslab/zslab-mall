import { describe, it, expect } from 'vitest'
import { formatPhone } from '~/lib/format/phone'

// FE-79 연락처 표시 형식. 표시 문자열만 만든다 — 판별할 수 없으면 원문 그대로.
describe('formatPhone', () => {
  it('+82 휴대폰 10·11자리 → 0으로 바꾼 뒤 3-3-4 · 3-4-4', () => {
    expect(formatPhone('+821012345678')).toBe('010-1234-5678')
    expect(formatPhone('+82 10-1234-5678')).toBe('010-1234-5678')
    expect(formatPhone('+821112345678')).toBe('011-1234-5678')
    expect(formatPhone('+82111234567')).toBe('011-123-4567')
  })

  it('010 → 3-4-4(하이픈 유무·공백 무관)', () => {
    expect(formatPhone('01012345678')).toBe('010-1234-5678')
    expect(formatPhone('010 1234 5678')).toBe('010-1234-5678')
    expect(formatPhone('010-2000-0001')).toBe('010-2000-0001')
  })

  it('02 → 2-3-4 · 2-4-4', () => {
    expect(formatPhone('021234567')).toBe('02-123-4567')
    expect(formatPhone('0212345678')).toBe('02-1234-5678')
    expect(formatPhone('+82212345678')).toBe('02-1234-5678')
  })

  it('031 등 그 밖의 지역번호 → 3-3-4 · 3-4-4', () => {
    expect(formatPhone('0311234567')).toBe('031-123-4567')
    expect(formatPhone('03112345678')).toBe('031-1234-5678')
  })

  it('판별 불가 → 원문 유지(마스킹·대표번호·자릿수 불일치·빈 값)', () => {
    for (const raw of ['010-****-1234', '1588-1234', '010-12-3456', '0101234567890', '', '연락처 없음']) {
      expect(formatPhone(raw)).toBe(raw)
    }
  })
})
