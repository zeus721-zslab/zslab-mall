import type { AdminAuditAction, AdminAuditChange, AdminAuditLog, AdminAuditTargetType } from '#layers/admin/app/types/admin-audit'
import { claimRejectReasonLabel, claimStatusLabel, CLAIM_INSPECTION_RESULT_LABELS, type ClaimInspectionResult } from '~/lib/constants/claim'
import { SETTLEMENT_STATUS_LABELS, type SettlementStatusCode } from '~/lib/constants/settlement'
import { ADMIN_DELIVERY_DIRECTION_LABEL, type AdminDeliveryDirection } from '#layers/admin/app/lib/constants/admin-delivery'
import {
  ADMIN_DELIVERY_CARRIER_LABEL,
  ADMIN_DELIVERY_STATUS_LABEL,
  type AdminDeliveryCarrier,
  type AdminDeliveryStatus,
} from '#layers/admin/app/lib/constants/admin-order'
import { RECONCILIATION_ISSUE_STATUS_LABEL, type ReconciliationIssueStatus } from '#layers/admin/app/lib/constants/reconciliation'
import { formatWon } from '#layers/admin/app/lib/format'
import { formatDateTime } from '~/lib/utils/datetime'

/**
 * 처리 이력 표시 순수 헬퍼(Track 101-A). 클레임 상세·정산 상세의 "처리 이력" 섹션이 공유한다.
 * BE는 감사 행을 그대로 내려주고(필드명·값 원본) 한글 라벨링은 전부 여기서 한다 — 값 변환·행위명 추론도 여기다(Track 103 D-214).
 */

/** 행위 유형 라벨(BE AuditLogAction 7값). */
export const ADMIN_AUDIT_ACTION_LABEL: Record<AdminAuditAction, string> = {
  CREATE: '생성',
  UPDATE: '변경',
  DELETE: '삭제',
  APPROVE: '승인',
  REJECT: '거부',
  LOGIN: '로그인',
  LOGOUT: '로그아웃',
}

/**
 * 행위자 역할 라벨(BE actor_role coarse 값). 값이 없으면 "시스템"으로 읽는다 — 스케줄러가 남긴 행은 actor_user_id·
 * actor_role이 비어 있다(자동 배송완료·자동 구매확정·월 정산 자동 생성).
 */
export function auditActorLabel(actorRole: string | undefined): string {
  // BE AuditContext.system()은 actorRole='SYSTEM'을 싣는다(스케줄러). 값이 아예 없는 과거 행도 같이 시스템으로 읽는다.
  if (!actorRole || actorRole === 'SYSTEM') return '시스템'
  if (actorRole.includes('ADMIN')) return '운영자'
  if (actorRole.includes('SELLER')) return '셀러'
  if (actorRole.includes('BUYER')) return '구매자'
  return actorRole
}

/**
 * 이력 행에 찍을 행위자 표기(Track 101-A 보완). 역할 + 이름이 기본이다. 이름이 없는 계정이면 이메일로 대신한다(Track 103 —
 * 이름 없이 만들어진 최초 관리자처럼 역할만으로는 누가 했는지 알 수 없다). 둘 다 없으면(스케줄러 행·회원 행이 사라진 과거 행) 역할만 쓴다.
 */
export function auditActorText(actorRole: string | undefined, actorName: string | undefined, actorEmail?: string): string {
  const role = auditActorLabel(actorRole)
  const who = actorName || actorEmail
  return who ? `${role} ${who}` : role
}

/** 감사 diff 필드명 → 화면 라벨. 미등록 필드는 원본 필드명을 그대로 쓴다(새 감사 소비처가 붙어도 화면이 깨지지 않게). */
const AUDIT_FIELD_LABEL: Record<string, string> = {
  status: '상태',
  rejectReasonCode: '거부 사유',
  rejectMemo: '거부 메모',
  pickedUpAt: '회수 확인 시각',
  inspectionResult: '검수 결과',
  restock: '재입고',
  reshipCarrier: '재발송 택배사',
  reshipTrackingNo: '재발송 송장',
  carrier: '택배사',
  trackingNo: '송장번호',
  direction: '배송 방향',
  claimId: '클레임',
  amount: '금액',
  reason: '사유',
  quantityOnHand: '보유 재고',
  quantityDelta: '증감',
  registeredOnBehalfOfBuyer: '구매자 대행 등록',
  paidAt: '지급 시각',
  periodStart: '정산 시작',
  periodEnd: '정산 종료',
  grossAmount: '매출',
  feeAmount: '수수료',
  refundAmount: '환불',
  carryoverAmount: '이월 차감',
  netAmount: '지급액',
  memo: '메모',
}

export function auditFieldLabel(field: string): string {
  return AUDIT_FIELD_LABEL[field] ?? field
}

/** 상태 값은 대상마다 뜻이 다르다(클레임·정산·배송). BE는 같은 키 status로 싣는다. */
const STATUS_VALUE_LABEL: Record<AdminAuditTargetType, (value: string) => string> = {
  CLAIM: claimStatusLabel,
  SETTLEMENT: (value) => SETTLEMENT_STATUS_LABELS[value as SettlementStatusCode] ?? value,
  DELIVERY: (value) => ADMIN_DELIVERY_STATUS_LABEL[value as AdminDeliveryStatus] ?? value,
  RECONCILIATION_ISSUE: (value) => RECONCILIATION_ISSUE_STATUS_LABEL[value as ReconciliationIssueStatus] ?? value,
}

/** 값 → 원 단위 금액. 숫자가 아니면 원본(방어). */
function wonText(value: string): string {
  const amount = Number(value)
  return Number.isFinite(amount) ? formatWon(amount) : value
}

function carrierText(value: string): string {
  return ADMIN_DELIVERY_CARRIER_LABEL[value as AdminDeliveryCarrier] ?? value
}

/** 대상과 무관하게 필드만으로 뜻이 정해지는 값 변환. 미등록 필드는 원본을 그대로 쓴다. */
const FIELD_VALUE_LABEL: Record<string, (value: string) => string> = {
  rejectReasonCode: claimRejectReasonLabel,
  inspectionResult: (value) => CLAIM_INSPECTION_RESULT_LABELS[value as ClaimInspectionResult] ?? value,
  restock: (value) => (value === 'true' ? '재입고함' : '재입고 안 함'),
  registeredOnBehalfOfBuyer: (value) => (value === 'true' ? '구매자 대신 등록' : '구매자 직접 등록'),
  carrier: carrierText,
  reshipCarrier: carrierText,
  direction: (value) => ADMIN_DELIVERY_DIRECTION_LABEL[value as AdminDeliveryDirection] ?? value,
  pickedUpAt: formatDateTime,
  paidAt: formatDateTime,
  periodStart: formatDateTime,
  periodEnd: formatDateTime,
  amount: wonText,
  grossAmount: wonText,
  feeAmount: wonText,
  refundAmount: wonText,
  carryoverAmount: wonText,
  netAmount: wonText,
}

/** 바뀐 값만으로 뜻이 통하는 필드 — "검수 결과 검수 합격"처럼 라벨이 겹치지 않게 값만 적는다. */
const VALUE_ONLY_FIELDS = new Set(['inspectionResult', 'restock', 'registeredOnBehalfOfBuyer'])

/**
 * 화면에 싣지 않는 내부 id. claimId는 클레임 이력에 합류한 배송 행에서 늘 그 클레임 자신이고, sellerId는 정산 상세가 이미 그 셀러이며,
 * bankAccountId는 운영자가 읽을 수 없는 계좌 행 id다(지급 계좌는 셀러 상세에서 본다).
 */
const HIDDEN_FIELDS = new Set(['claimId', 'sellerId', 'bankAccountId'])

/** 감사 값 1개를 사람이 읽는 표기로 바꾼다. 값이 없으면 "—", 변환 규칙이 없으면 원본. */
export function auditValueText(targetType: AdminAuditTargetType, field: string, value: string | null | undefined): string {
  if (value === null || value === undefined) return '—'
  const convert = field === 'status' ? STATUS_VALUE_LABEL[targetType] : FIELD_VALUE_LABEL[field]
  return convert ? convert(value) : value
}

/** 변경 1건을 "라벨 before → after" 또는 신규 값만 있으면 "라벨 after"로 만든다. 값은 사람이 읽는 표기로 바꾼다. */
export function auditChangeText(change: AdminAuditChange, targetType: AdminAuditTargetType): string {
  const after = auditValueText(targetType, change.field, change.after)
  if (VALUE_ONLY_FIELDS.has(change.field)) return after
  const label = auditFieldLabel(change.field)
  if (change.before === null || change.before === undefined) {
    return `${label} ${after}`
  }
  return `${label} ${auditValueText(targetType, change.field, change.before)} → ${after}`
}

/** 변경 목록을 한 줄 요약으로 잇는다. 변경이 없으면 빈 문자열(섹션이 "변경 내역 없음"을 대신 표시한다). */
export function auditChangeSummary(changes: AdminAuditChange[], targetType: AdminAuditTargetType): string {
  return changes
    .filter((change) => !HIDDEN_FIELDS.has(change.field))
    .map((change) => auditChangeText(change, targetType))
    .join(' · ')
}

/**
 * 구체 행위명(Track 103 D-214 — AuditLogAction enum 확장 대신 바뀐 필드로 추론). BE는 회수 확인·검수·정산 확정·지급을 모두 UPDATE로
 * 싣는다. 적재 지점별 diff 키가 서로 겹치지 않아(ClaimService·SettlementTransitionService·배송 적재 5곳) 키만으로 가를 수 있다.
 * 추론할 수 없으면 기존 행위 라벨(변경 등)을 쓴다.
 */
export function auditActionText(log: Pick<AdminAuditLog, 'targetType' | 'action' | 'changes'>): string {
  const has = (field: string): boolean => log.changes.some((change) => change.field === field)
  const afterOf = (field: string): string | null | undefined => log.changes.find((change) => change.field === field)?.after
  if (log.targetType === 'CLAIM' && log.action === 'UPDATE') {
    if (has('inspectionResult')) return '검수'
    if (has('pickedUpAt')) return '회수 확인'
  }
  if (log.targetType === 'SETTLEMENT') {
    if (log.action === 'CREATE') return '정산 생성'
    if (log.action === 'DELETE') return '정산 삭제'
    if (log.action === 'UPDATE' && afterOf('status') === 'CONFIRMED') return '정산 확정'
    if (log.action === 'UPDATE' && afterOf('status') === 'PAID') return '지급완료'
  }
  if (log.targetType === 'DELIVERY') {
    if (log.action === 'CREATE' && afterOf('registeredOnBehalfOfBuyer') === 'true') return '회수 송장 대행 등록'
    if (log.action === 'CREATE' && afterOf('direction') === 'OUTBOUND') return '교환품 발송'
    if (log.action === 'CREATE') return '송장 등록'
    // DiffBuilder는 바뀐 키만 싣는다 — 택배사만 정정하면 trackingNo가 없다.
    if (log.action === 'UPDATE' && (has('trackingNo') || has('carrier'))) return '송장 정정'
    if (log.action === 'UPDATE' && afterOf('status') === 'DELIVERED') return '배송완료'
  }
  if (log.targetType === 'RECONCILIATION_ISSUE' && log.action === 'UPDATE' && afterOf('status') === 'RESOLVED') return '불일치 해결'
  return ADMIN_AUDIT_ACTION_LABEL[log.action]
}
