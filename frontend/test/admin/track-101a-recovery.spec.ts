import { describe, it, expect } from 'vitest'
import {
  INVENTORY_ADJUST_WARN_THRESHOLD,
  isLargeInventoryAdjust,
  largeInventoryAdjustMessage,
  largeInventoryAdjusts,
} from '~/lib/utils/inventory-adjust'
import { isWaitingForReturnShipment, pickupWaitingLabel } from '#layers/admin/app/lib/admin-claim-view'
import {
  ADMIN_AUDIT_ACTION_LABEL,
  auditActionText,
  auditActorLabel,
  auditActorText,
  auditChangeSummary,
  auditChangeText,
  auditFieldLabel,
} from '#layers/admin/app/lib/admin-audit-view'
import { isRejection, rejectConfirmMessage } from '#layers/admin/app/lib/admin-product-view'
import { toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { toSellerErrorMessage } from '#layers/seller/app/lib/seller-error-message'

/**
 * Track 101-A 예외·실수 복구 순수 함수 테스트. 재고 대량 조정 판정·회수 대기 표시·처리 이력 포맷·422 문구 우선순위를 다룬다.
 */

describe('재고 대량 조정 판정(inventory-adjust)', () => {
  it('임계 미만은 경고 대상이 아니다', () => {
    expect(isLargeInventoryAdjust(INVENTORY_ADJUST_WARN_THRESHOLD - 1)).toBe(false)
    expect(isLargeInventoryAdjust(1)).toBe(false)
  })

  it('임계 이상은 부호와 무관하게 경고 대상이다(출고 -N도 포함)', () => {
    expect(isLargeInventoryAdjust(INVENTORY_ADJUST_WARN_THRESHOLD)).toBe(true)
    expect(isLargeInventoryAdjust(-INVENTORY_ADJUST_WARN_THRESHOLD)).toBe(true)
  })

  it('NaN·Infinity는 경고 대상이 아니다(폼 검증이 먼저 걸러낸다)', () => {
    expect(isLargeInventoryAdjust(Number.NaN)).toBe(false)
    expect(isLargeInventoryAdjust(Number.POSITIVE_INFINITY)).toBe(false)
  })

  it('임계 이상인 조정만 추린다', () => {
    const lines = [
      { label: 'A', delta: 5 },
      { label: 'B', delta: 2000 },
      { label: 'C', delta: -3000 },
    ]
    expect(largeInventoryAdjusts(lines).map((line) => line.label)).toEqual(['B', 'C'])
  })

  it('경고 문구는 대상별 부호를 붙여 나열한다', () => {
    const message = largeInventoryAdjustMessage([{ label: 'OPT-1', delta: 2000 }, { label: 'OPT-2', delta: -3000 }])
    expect(message).toContain(`${INVENTORY_ADJUST_WARN_THRESHOLD}개 이상`)
    expect(message).toContain('OPT-1: +2000개')
    expect(message).toContain('OPT-2: -3000개')
  })

  it('대상이 없으면 빈 문자열(다이얼로그 미노출)', () => {
    expect(largeInventoryAdjustMessage([])).toBe('')
  })
})

describe('회수 대기 표시(pickupWaitingLabel)', () => {
  it('승인된 반품인데 회수 송장·회수 확인이 모두 없으면 대기다', () => {
    expect(isWaitingForReturnShipment({ type: 'RETURN', status: 'APPROVED' })).toBe(true)
    expect(pickupWaitingLabel({ type: 'RETURN', status: 'APPROVED' })).toBe('구매자 회수 송장 등록 대기')
  })

  it('교환도 회수 기반이라 같은 판정이다', () => {
    expect(isWaitingForReturnShipment({ type: 'EXCHANGE', status: 'APPROVED' })).toBe(true)
  })

  it('회수 송장이 등록됐거나 회수 확인이 끝났으면 대기가 아니다', () => {
    expect(isWaitingForReturnShipment({ type: 'RETURN', status: 'APPROVED', returnShipment: { carrier: 'CJ' } })).toBe(false)
    expect(isWaitingForReturnShipment({ type: 'RETURN', status: 'APPROVED', pickedUpAt: '2026-09-23T10:00:00+09:00' })).toBe(false)
  })

  it('취소 유형·미승인·종결 상태는 대기가 아니다(다른 이유로 액션이 없는 행)', () => {
    expect(isWaitingForReturnShipment({ type: 'CANCEL', status: 'APPROVED' })).toBe(false)
    expect(isWaitingForReturnShipment({ type: 'RETURN', status: 'REQUESTED' })).toBe(false)
    expect(isWaitingForReturnShipment({ type: 'RETURN', status: 'REJECTED' })).toBe(false)
    expect(isWaitingForReturnShipment({ type: 'RETURN', status: 'COMPLETED' })).toBe(false)
    expect(pickupWaitingLabel({ type: 'CANCEL', status: 'APPROVED' })).toBeNull()
  })
})

describe('처리 이력 표시(admin-audit-view)', () => {
  it('행위 유형은 한글 라벨로 바꾼다', () => {
    expect(ADMIN_AUDIT_ACTION_LABEL.APPROVE).toBe('승인')
    expect(ADMIN_AUDIT_ACTION_LABEL.UPDATE).toBe('변경')
  })

  it('행위자 역할이 없으면 시스템(스케줄러 적재 행)', () => {
    expect(auditActorLabel(undefined)).toBe('시스템')
    expect(auditActorLabel('SYSTEM')).toBe('시스템')
    expect(auditActorLabel('ADMIN')).toBe('운영자')
    expect(auditActorLabel('SELLER')).toBe('셀러')
  })

  it('알 수 없는 역할·필드는 원본을 그대로 쓴다(새 감사 소비처가 붙어도 화면이 깨지지 않게)', () => {
    expect(auditActorLabel('OPERATOR_X')).toBe('OPERATOR_X')
    expect(auditFieldLabel('someNewField')).toBe('someNewField')
  })

  it('before가 없으면 신규 값만 적는다', () => {
    expect(auditChangeText({ field: 'trackingNo', before: null, after: 'RTN-1' }, 'DELIVERY')).toBe('송장번호 RTN-1')
  })

  // Track 103: 값은 사람이 읽는 표기로 바꾼다(원시 enum 노출 제거).
  it('before가 있으면 화살표로 잇고, 상태 값은 대상별 라벨로 바꾼다', () => {
    expect(auditChangeText({ field: 'status', before: 'REQUESTED', after: 'APPROVED' }, 'CLAIM')).toBe('상태 요청 → 승인')
    expect(auditChangeText({ field: 'status', before: 'PENDING', after: 'CONFIRMED' }, 'SETTLEMENT')).toBe('상태 확정 대기 → 확정')
    expect(auditChangeText({ field: 'status', before: 'SHIPPING', after: 'DELIVERED' }, 'DELIVERY')).toBe('상태 배송중 → 배송완료')
  })

  it('여러 변경은 가운뎃점으로 잇고, 없으면 빈 문자열', () => {
    expect(auditChangeSummary([
      { field: 'status', before: 'APPROVED', after: 'REJECTED' },
      { field: 'rejectReasonCode', before: null, after: 'OUT_OF_POLICY' },
    ], 'CLAIM')).toBe('상태 승인 → 거부 · 거부 사유 정책상 불가')
    expect(auditChangeSummary([], 'CLAIM')).toBe('')
  })
})

describe('처리 이력 값 변환(Track 103)', () => {
  it('검수 결과·재입고는 값만으로 적는다(라벨 중복 없음)', () => {
    expect(auditChangeSummary([
      { field: 'inspectionResult', before: null, after: 'PASS' },
      { field: 'restock', before: null, after: 'true' },
    ], 'CLAIM')).toBe('검수 합격 · 재입고함')
    expect(auditChangeText({ field: 'restock', before: null, after: 'false' }, 'CLAIM')).toBe('재입고 안 함')
    expect(auditChangeText({ field: 'inspectionResult', before: null, after: 'FAIL' }, 'CLAIM')).toBe('검수 불합격')
  })

  it('시각은 표준 형식(나노초 ISO도 앞 16자)·금액은 원 단위', () => {
    expect(auditChangeText({ field: 'pickedUpAt', before: null, after: '2026-09-23T13:55:28.767265440' }, 'CLAIM'))
      .toBe('회수 확인 시각 2026.09.23 13:55')
    expect(auditChangeText({ field: 'netAmount', before: null, after: '89000' }, 'SETTLEMENT')).toBe('지급액 89,000원')
  })

  it('택배사·배송 방향·대행 등록은 한글 표기, 클레임 id는 숨긴다', () => {
    expect(auditChangeSummary([
      { field: 'claimId', before: null, after: '29' },
      { field: 'direction', before: null, after: 'RETURN' },
      { field: 'carrier', before: null, after: 'CJ' },
      { field: 'registeredOnBehalfOfBuyer', before: null, after: 'true' },
    ], 'DELIVERY')).toBe('배송 방향 회수 · 택배사 CJ대한통운 · 구매자 대신 등록')
  })

  it('정산 이력의 셀러·계좌 내부 id는 숨긴다', () => {
    expect(auditChangeSummary([
      { field: 'status', before: 'CONFIRMED', after: 'PAID' },
      { field: 'bankAccountId', before: null, after: '1' },
    ], 'SETTLEMENT')).toBe('상태 확정 → 지급완료')
    expect(auditChangeSummary([
      { field: 'sellerId', before: null, after: '12' },
      { field: 'grossAmount', before: null, after: '100000' },
    ], 'SETTLEMENT')).toBe('매출 100,000원')
  })
})

describe('처리 이력 행위명 추론(auditActionText·Track 103)', () => {
  const change = (field: string, after: string, before: string | null = null) => ({ field, before, after })

  it('클레임 UPDATE는 바뀐 필드로 회수 확인·검수를 가른다', () => {
    expect(auditActionText({ targetType: 'CLAIM', action: 'UPDATE', changes: [change('pickedUpAt', '2026-09-23T13:55')] })).toBe('회수 확인')
    expect(auditActionText({ targetType: 'CLAIM', action: 'UPDATE', changes: [change('inspectionResult', 'PASS')] })).toBe('검수')
    expect(auditActionText({ targetType: 'CLAIM', action: 'APPROVE', changes: [change('status', 'APPROVED', 'REQUESTED')] })).toBe('승인')
  })

  it('정산 UPDATE는 바뀐 상태로 확정·지급완료를 가른다', () => {
    expect(auditActionText({ targetType: 'SETTLEMENT', action: 'UPDATE', changes: [change('status', 'CONFIRMED', 'PENDING')] })).toBe('정산 확정')
    expect(auditActionText({ targetType: 'SETTLEMENT', action: 'UPDATE', changes: [change('status', 'PAID', 'CONFIRMED')] })).toBe('지급완료')
  })

  it('배송 행은 대행 등록·교환품 발송·송장 정정·배송완료로 적는다', () => {
    expect(auditActionText({ targetType: 'DELIVERY', action: 'CREATE', changes: [change('direction', 'RETURN'), change('registeredOnBehalfOfBuyer', 'true')] }))
      .toBe('회수 송장 대행 등록')
    expect(auditActionText({ targetType: 'DELIVERY', action: 'CREATE', changes: [change('direction', 'OUTBOUND')] })).toBe('교환품 발송')
    expect(auditActionText({ targetType: 'DELIVERY', action: 'UPDATE', changes: [change('trackingNo', 'B', 'A')] })).toBe('송장 정정')
    // 택배사만 바꾼 정정은 diff에 trackingNo가 없다(DiffBuilder는 바뀐 키만)
    expect(auditActionText({ targetType: 'DELIVERY', action: 'UPDATE', changes: [change('carrier', 'HANJIN', 'CJ'), change('reason', '오기')] })).toBe('송장 정정')
    expect(auditActionText({ targetType: 'DELIVERY', action: 'UPDATE', changes: [change('status', 'DELIVERED', 'SHIPPING')] })).toBe('배송완료')
  })

  it('추론할 수 없으면 기존 행위 라벨(변경)', () => {
    expect(auditActionText({ targetType: 'CLAIM', action: 'UPDATE', changes: [] })).toBe('변경')
  })
})

describe('422 문구 우선순위(CLAIM_STATE_INVALID)', () => {
  const detail = '회수 송장이 등록되지 않아 회수 확인할 수 없습니다: claimId=42'

  it('관리자: BE detail이 코드 문구를 덮지 않고 그대로 나온다', () => {
    expect(toAdminErrorMessage({ data: { code: 'CLAIM_STATE_INVALID', detail } })).toBe(detail)
  })

  it('셀러: 같은 규칙을 적용한다', () => {
    expect(toSellerErrorMessage({ data: { code: 'CLAIM_STATE_INVALID', detail } })).toBe(detail)
  })

  it('detail이 비면 기존 코드 문구로 폴백한다', () => {
    expect(toAdminErrorMessage({ data: { code: 'CLAIM_STATE_INVALID' } })).toContain('클레임')
    expect(toSellerErrorMessage({ data: { code: 'CLAIM_STATE_INVALID' } })).toContain('발송할 수 없습니다')
  })

  it('다른 코드는 기존대로 코드 문구가 detail보다 우선한다', () => {
    expect(toAdminErrorMessage({ data: { code: 'PRODUCT_HAS_ORDER_HISTORY', detail: '내부 메시지' } }))
      .toContain('판매중지로 전환하세요')
    expect(toSellerErrorMessage({ data: { code: 'ORDER_ITEM_INVALID_STATE', detail: '내부 메시지' } }))
      .toContain('허용되지 않는 처리')
  })
})

describe('상품 거부 확인 조건(목록·상세 공용)', () => {
  it('REJECTED 목표만 확인 대상이다', () => {
    expect(isRejection('REJECTED')).toBe(true)
  })

  it('가역 전이(승인·판매중지·재판매)는 확인 없이 즉시 반영한다', () => {
    expect(isRejection('SALE')).toBe(false)
    expect(isRejection('STOPPED')).toBe(false)
  })

  it('확인 문구는 불가역과 철회 경로를 함께 알린다', () => {
    const message = rejectConfirmMessage('E2E 티셔츠')
    expect(message).toContain('E2E 티셔츠을(를) 거부합니다.')
    expect(message).toContain('거부 철회')
  })
})

describe('이력 행위자 표기(auditActorText)', () => {
  it('역할과 이름을 함께 적는다', () => {
    expect(auditActorText('ADMIN', '감사운영자')).toBe('운영자 감사운영자')
    expect(auditActorText('SELLER', '통합셀러')).toBe('셀러 통합셀러')
  })

  it('이름이 없으면 역할만 적는다(스케줄러 행·해소 불가한 과거 행)', () => {
    expect(auditActorText('SYSTEM', undefined)).toBe('시스템')
    expect(auditActorText('ADMIN', undefined)).toBe('운영자')
    expect(auditActorText(undefined, undefined)).toBe('시스템')
  })

  it('이름이 없으면 이메일로 대신한다(이름 없이 만들어진 계정·Track 103)', () => {
    expect(auditActorText('ADMIN', undefined, 'admin@example.test')).toBe('운영자 admin@example.test')
    expect(auditActorText('ADMIN', '감사운영자', 'admin@example.test')).toBe('운영자 감사운영자')
  })

  it('역할을 모르더라도 이름이 있으면 함께 적는다(원본 역할 문자열 유지)', () => {
    expect(auditActorText('OPERATOR_X', '홍길동')).toBe('OPERATOR_X 홍길동')
  })
})
