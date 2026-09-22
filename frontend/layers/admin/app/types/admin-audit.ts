/**
 * 관리자 처리 이력 타입(Track 101-A·BE AdminAuditLogResponse 1:1). 클레임 상세·정산 상세의 "처리 이력" 섹션이 쓴다.
 * 시각 문자열은 +09:00 오프셋 직렬화(KstOffsetSerializer)이며 formatDateTime으로만 표시한다.
 */

/** 감사 행위 유형(BE AuditLogAction 7값). */
export type AdminAuditAction = 'CREATE' | 'UPDATE' | 'DELETE' | 'APPROVE' | 'REJECT' | 'LOGIN' | 'LOGOUT'

/** 변경 1건. 값이 없던 필드는 null(신규 생성의 before 등). */
export interface AdminAuditChange {
  field: string
  before: string | null
  after: string | null
}

export interface AdminAuditLog {
  auditPublicId: string
  occurredAt: string
  /** BE actor_role(coarse). 값이 없으면 생략된다. */
  actorRole?: string
  /** 행위자 이름. 시스템 행(스케줄러)·해소 불가한 과거 행은 생략된다 — 그 경우 역할만 표시한다. */
  actorName?: string
  /** 행위자 이메일(동명이인 구분용 보조 표기). 이름과 같은 조건으로 생략된다. */
  actorEmail?: string
  action: AdminAuditAction
  changes: AdminAuditChange[]
}

/** PagedResponse 5필드 계약. */
export interface AdminAuditLogPage {
  items: AdminAuditLog[]
  page: number
  size: number
  totalCount: number
  hasNext: boolean
}
