import { describe, it, expect } from 'vitest'
import { formatDateTime } from '~/lib/utils/datetime'

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
