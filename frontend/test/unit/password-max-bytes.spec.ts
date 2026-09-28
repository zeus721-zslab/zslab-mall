import { describe, it, expect } from 'vitest'
import { PASSWORD_MAX_BYTES_MESSAGE, exceedsPasswordMaxBytes } from '~/lib/constants/account'

// D-233: BE PasswordPolicy와 같은 UTF-8 72바이트 상한. 한글은 3바이트라 24자 = 72바이트(통과), 25자 = 75바이트(초과).
describe('exceedsPasswordMaxBytes', () => {
  it('영문 72바이트 통과 · 73바이트 초과', () => {
    expect(exceedsPasswordMaxBytes('a'.repeat(72))).toBe(false)
    expect(exceedsPasswordMaxBytes('a'.repeat(73))).toBe(true)
  })

  it('한글 24자(72바이트) 통과 · 25자(75바이트) 초과', () => {
    expect(exceedsPasswordMaxBytes('가'.repeat(24))).toBe(false)
    expect(exceedsPasswordMaxBytes('가'.repeat(25))).toBe(true)
  })

  it('안내 문구는 BE 정책 문구와 같다', () => {
    expect(PASSWORD_MAX_BYTES_MESSAGE).toBe('비밀번호는 72바이트 이하여야 합니다(영문 72자, 한글 약 24자).')
  })
})
