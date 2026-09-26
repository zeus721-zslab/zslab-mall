import { describe, it, expect } from 'vitest'
import { DEMO_ACCOUNT_PROTECTED_MESSAGE, demoAccountProtectedMessage } from '~/lib/constants/account'

// D-230: 구매자 비밀번호 변경·탈퇴 화면은 403 DEMO_ACCOUNT_PROTECTED면 기존 인라인 알림(errorMessage)에 데모 안내 문구를 쓴다.
describe('demoAccountProtectedMessage', () => {
  it('DEMO_ACCOUNT_PROTECTED 코드면 안내 문구 · 그 외 코드·비ProblemDetail·null은 null(기존 분기 유지)', () => {
    expect(demoAccountProtectedMessage({ statusCode: 403, data: { code: 'DEMO_ACCOUNT_PROTECTED' } })).toBe(DEMO_ACCOUNT_PROTECTED_MESSAGE)
    expect(DEMO_ACCOUNT_PROTECTED_MESSAGE).toBe('데모 계정은 이 기능을 사용할 수 없습니다.')
    expect(demoAccountProtectedMessage({ statusCode: 400, data: { code: 'MALFORMED_REQUEST' } })).toBeNull()
    expect(demoAccountProtectedMessage(new Error('network'))).toBeNull()
    expect(demoAccountProtectedMessage(null)).toBeNull()
  })
})
