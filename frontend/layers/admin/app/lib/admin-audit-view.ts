import type { AdminAuditAction, AdminAuditChange } from '#layers/admin/app/types/admin-audit'

/**
 * 처리 이력 표시 순수 헬퍼(Track 101-A). 클레임 상세·정산 상세의 "처리 이력" 섹션이 공유한다.
 * BE는 감사 행을 그대로 내려주고(필드명·값 원본) 한글 라벨링은 전부 여기서 한다.
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
 * 이력 행에 찍을 행위자 표기(Track 101-A 보완). 역할 + 이름이 기본이고, 이름이 없으면 역할만 쓴다 —
 * 스케줄러 행(행위자 없음)과 회원 행이 사라진 과거 행이 그 경우다. 이메일은 행에 넣지 않고 title 속성으로만 보조한다.
 */
export function auditActorText(actorRole: string | undefined, actorName: string | undefined): string {
  const role = auditActorLabel(actorRole)
  return actorName ? `${role} ${actorName}` : role
}

/** 감사 diff 필드명 → 화면 라벨. 미등록 필드는 원본 필드명을 그대로 쓴다(새 감사 소비처가 붙어도 화면이 깨지지 않게). */
const AUDIT_FIELD_LABEL: Record<string, string> = {
  status: '상태',
  rejectReasonCode: '거부 사유',
  rejectMemo: '거부 메모',
  pickedUpAt: '회수 확인',
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
}

export function auditFieldLabel(field: string): string {
  return AUDIT_FIELD_LABEL[field] ?? field
}

/** 변경 1건을 "라벨 before → after" 또는 신규 값만 있으면 "라벨 after"로 만든다. */
export function auditChangeText(change: AdminAuditChange): string {
  const label = auditFieldLabel(change.field)
  if (change.before === null || change.before === undefined) {
    return `${label} ${change.after ?? '—'}`
  }
  return `${label} ${change.before} → ${change.after ?? '—'}`
}

/** 변경 목록을 한 줄 요약으로 잇는다. 변경이 없으면 빈 문자열(섹션이 "변경 내역 없음"을 대신 표시한다). */
export function auditChangeSummary(changes: AdminAuditChange[]): string {
  return changes.map(auditChangeText).join(' · ')
}
