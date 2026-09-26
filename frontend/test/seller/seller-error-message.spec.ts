import { describe, it, expect } from 'vitest'
import {
  extractErrorCode,
  extractErrorStatus,
  isSellerSuspendedError,
  mapFieldErrors,
  toSellerErrorMessage,
} from '#layers/seller/app/lib/seller-error-message'

// Track 90-B-3(FE-44 §8 이월): 셀러 에러 문구 — 코드 우선 → HTTP 상태 폴백(401·403·404·409·422) → detail → 일반 문구. 403 SELLER_SUSPENDED는 호출부 토스트 문구.
function fetchError(status: number, data?: unknown): unknown {
  return { status, data }
}

describe('toSellerErrorMessage', () => {
  it('SELLER_SUSPENDED(403) → 정지 셀러 변경 불가 문구(배너와 별개로 호출부가 토스트에 쓴다)', () => {
    const error = fetchError(403, { code: 'SELLER_SUSPENDED', detail: '정지 셀러' })
    expect(isSellerSuspendedError(error)).toBe(true)
    expect(toSellerErrorMessage(error)).toBe('정지 상태의 셀러는 변경 작업을 할 수 없습니다. 조회만 가능하며 문의는 관리자에게 하세요.')
  })

  it('코드 매핑: 401·404·422 계열 도메인 코드', () => {
    expect(toSellerErrorMessage(fetchError(401, { code: 'UNAUTHENTICATED' }))).toBe('로그인이 필요합니다.')
    expect(toSellerErrorMessage(fetchError(404, { code: 'ORDER_NOT_FOUND' }))).toContain('주문 품목을 찾을 수 없습니다')
    expect(toSellerErrorMessage(fetchError(404, { code: 'SETTLEMENT_NOT_FOUND' }))).toContain('확정 대기 정산은 조회되지 않습니다')
    expect(toSellerErrorMessage(fetchError(422, { code: 'ORDER_ITEM_INVALID_STATE' }))).toContain('발송은 결제완료 품목만')
    expect(toSellerErrorMessage(fetchError(422, { code: 'DELIVERY_INVALID_STATE' }))).toContain('배송완료·송장 정정은 배송중만')
  })

  it('미지 코드·코드 없음 → HTTP 상태 폴백(401·403·404·409·422)', () => {
    expect(toSellerErrorMessage(fetchError(401))).toBe('로그인이 필요합니다.')
    expect(toSellerErrorMessage(fetchError(403, { code: 'SOMETHING_NEW' }))).toBe('권한이 없습니다.')
    expect(toSellerErrorMessage(fetchError(404))).toBe('대상을 찾을 수 없습니다.')
    expect(toSellerErrorMessage(fetchError(409))).toContain('충돌하는 요청')
    expect(toSellerErrorMessage(fetchError(422))).toBe('현재 상태에서 허용되지 않는 처리입니다.')
    // response.status 형태(FetchError 변형)도 읽는다
    expect(toSellerErrorMessage({ response: { status: 404 } })).toBe('대상을 찾을 수 없습니다.')
  })

  it('상태 폴백도 없으면 서버 detail → 일반 문구 · 네트워크 오류(null·비객체)도 안전', () => {
    expect(toSellerErrorMessage(fetchError(500, { code: 'X', detail: '서버 상세' }))).toBe('서버 상세')
    expect(toSellerErrorMessage(fetchError(500, { detail: '' }))).toBe('요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.')
    expect(toSellerErrorMessage(null)).toBe('요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.')
    expect(toSellerErrorMessage(new Error('boom'))).toBe('요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.')
  })

  it('extractErrorCode·extractErrorStatus·isSellerSuspendedError', () => {
    expect(extractErrorCode(fetchError(400, { code: 'VALIDATION_FAILED' }))).toBe('VALIDATION_FAILED')
    expect(extractErrorCode(fetchError(400, { code: 123 }))).toBeNull()
    expect(extractErrorStatus(fetchError(422))).toBe(422)
    expect(extractErrorStatus({})).toBeNull()
    expect(isSellerSuspendedError(fetchError(403, { code: 'FORBIDDEN' }))).toBe(false)
  })
})

describe('mapFieldErrors', () => {
  it('fieldErrors → 필드별 첫 메시지 · 빈 메시지는 기본 문구 · 없으면 빈 객체', () => {
    expect(mapFieldErrors(fetchError(400, { fieldErrors: [
      { field: 'trackingNo', message: '필수' }, { field: 'trackingNo', message: '두 번째' }, { field: 'reason', message: '' },
    ] }))).toEqual({ trackingNo: '필수', reason: '입력값을 확인해 주세요.' })
    expect(mapFieldErrors(fetchError(400, {}))).toEqual({})
    expect(mapFieldErrors(null)).toEqual({})
  })

  it('D-230: 403 DEMO_ACCOUNT_PROTECTED(셀러 데모 계정 비밀번호 변경) → 데모 안내 문구(403 폴백 아님)', () => {
    expect(toSellerErrorMessage(fetchError(403, { code: 'DEMO_ACCOUNT_PROTECTED', detail: 'x' }))).toBe('데모 계정은 이 기능을 사용할 수 없습니다.')
  })
})
