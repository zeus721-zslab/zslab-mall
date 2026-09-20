import type { SellerClaimSummary } from '#layers/seller/app/types/seller-claim'
import { CLAIM_REASON_LABELS, type ClaimReasonCode } from '~/lib/constants/claim'

/** 클레임 사유 코드 → 라벨(사용자 상수 재사용·매핑에 없는 값은 원본 폴백). */
export function claimReasonLabel(code: string): string {
  return CLAIM_REASON_LABELS[code as ClaimReasonCode] ?? code
}

/** 목록 행 상품 표기: 상품명(옵션이 있으면 " · 옵션"). */
export function claimProductLabel(claim: Pick<SellerClaimSummary, 'productName' | 'optionLabel'>): string {
  return claim.optionLabel ? `${claim.productName} · ${claim.optionLabel}` : claim.productName
}

/** 첨부 개수 표기(0장은 빈 문자열 → 표에서 생략). */
export function attachmentCountLabel(count: number): string {
  return count > 0 ? `첨부 ${count}장` : ''
}

/** 상태 타임라인 1단계(읽기 전용 표시). at은 BE 시각이 있을 때만. */
export interface ClaimTimelineStep {
  key: 'requested' | 'processed' | 'completed'
  label: string
  at: string | null
  state: 'done' | 'current' | 'pending'
}

/**
 * 클레임 상태 → 타임라인 3단계(요청 → 승인/거절 → 완료). BE 상태기계(REQUESTED → APPROVED → COMPLETED / REQUESTED·검수 FAIL → REJECTED)를 그대로
 * 표시만 한다. processedAt은 승인·거절 시각이며 완료 전이(markCompleted)가 덮어쓰므로 COMPLETED에서는 완료 시각으로 쓴다. REJECTED는 종결이라 완료 단계가 없다.
 */
export function claimTimeline(claim: Pick<SellerClaimSummary, 'status' | 'requestedAt' | 'processedAt'>): ClaimTimelineStep[] {
  const requested: ClaimTimelineStep = { key: 'requested', label: '요청 접수', at: claim.requestedAt, state: 'done' }
  const processedAt = claim.processedAt ?? null
  switch (claim.status) {
    case 'REQUESTED':
      return [requested, { key: 'processed', label: '관리자 처리 대기', at: null, state: 'current' }, { key: 'completed', label: '완료', at: null, state: 'pending' }]
    case 'APPROVED':
      return [requested, { key: 'processed', label: '승인', at: processedAt, state: 'done' }, { key: 'completed', label: '처리 진행 중', at: null, state: 'current' }]
    case 'REJECTED':
      return [requested, { key: 'processed', label: '거절', at: processedAt, state: 'done' }]
    case 'COMPLETED':
      return [requested, { key: 'processed', label: '승인', at: null, state: 'done' }, { key: 'completed', label: '완료', at: processedAt, state: 'done' }]
  }
}
