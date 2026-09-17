import type { ClaimStatus } from '~/lib/constants/claim'
import type { ClaimDetail } from '~/types/claim'

/**
 * 클레임 상세 진행 타임라인 계산(FE-29·FE-30·순수 함수). 취소는 요청→승인→완료 3단(REJECTED는 요청→거절), 반품은
 * 요청→승인→회수 송장→회수 확인→검수→환불 완료 6단이며 검수 불합격은 검수 단계에서 종결한다(D-170 §2). 교환은
 * 신청→승인→회수→검수→교환품 발송→배송완료→완료 7단(FE-30-2·D-177)이며 검수 불합격은 검수 단계에서 종결한다.
 * 단계 시각은 BE가 주는 값만 쓴다(요청 requestedAt·회수 송장 shippedAt·회수 확인 pickedUpAt·교환품 발송/배송완료 reshipment·종결 processedAt).
 * 승인·검수 시각은 데이터가 없어 표기하지 않는다(추정 금지).
 */
export type TimelineStepState = 'done' | 'current' | 'upcoming'

export interface TimelineStep {
  label: string
  state: TimelineStepState
  at: string | null
}

const RETURN_LABELS = ['요청', '승인', '회수 송장', '회수 확인', '검수', '환불 완료'] as const
const EXCHANGE_LABELS = ['신청', '승인', '회수', '검수', '교환품 발송', '배송완료', '완료'] as const

type TimelineSource = Pick<
  ClaimDetail,
  'claimType' | 'status' | 'requestedAt' | 'processedAt' | 'returnShipment' | 'pickedUpAt' | 'inspectionResult' | 'reshipment'
>

function withStates(labels: readonly string[], currentIndex: number, ats: (string | null)[]): TimelineStep[] {
  return labels.map((label, index) => ({
    label,
    state: index < currentIndex ? 'done' : index === currentIndex ? 'current' : 'upcoming',
    at: ats[index] ?? null,
  }))
}

/** 반품이 도달한 단계 인덱스(RETURN_LABELS 기준). */
function returnStageIndex(detail: TimelineSource): number {
  if (detail.status === 'REQUESTED') return 0
  if (detail.status === 'COMPLETED') return 5
  if (!detail.returnShipment) return 1
  if (!detail.pickedUpAt) return 2
  if (!detail.inspectionResult) return 3
  return 4
}

/** 교환이 도달한 단계 인덱스(EXCHANGE_LABELS 기준). 회수는 회수 송장 등록~회수 확인, 교환품 발송은 OUTBOUND 등록(reshipment). */
function exchangeStageIndex(detail: TimelineSource): number {
  if (detail.status === 'REQUESTED') return 0
  if (detail.status === 'COMPLETED') return 6
  if (!detail.returnShipment && !detail.pickedUpAt) return 1
  if (!detail.pickedUpAt) return 2
  if (!detail.inspectionResult) return 3
  if (!detail.reshipment) return 4
  return 5
}

export function claimTimeline(detail: TimelineSource): TimelineStep[] {
  if (detail.claimType === 'EXCHANGE') {
    if (detail.status === 'REJECTED' && detail.inspectionResult === 'FAIL') {
      return withStates(
        [...EXCHANGE_LABELS.slice(0, 3), '검수 불합격'],
        3,
        [detail.requestedAt, null, detail.pickedUpAt ?? detail.returnShipment?.shippedAt ?? null, detail.processedAt],
      )
    }
    if (detail.status === 'REJECTED') {
      return withStates(['신청', '거절'], 1, [detail.requestedAt, detail.processedAt])
    }
    const current = exchangeStageIndex(detail)
    return withStates(EXCHANGE_LABELS, current, [
      detail.requestedAt,
      null,
      detail.pickedUpAt ?? detail.returnShipment?.shippedAt ?? null,
      null,
      detail.reshipment?.shippedAt ?? null,
      detail.reshipment?.deliveredAt ?? null,
      current === 6 ? detail.processedAt : null,
    ])
  }

  if (detail.claimType === 'RETURN') {
    if (detail.status === 'REJECTED' && detail.inspectionResult === 'FAIL') {
      // 검수 불합격 종결: 검수 단계를 "검수 불합격"으로 바꿔 5단으로 끝낸다.
      return withStates(
        [...RETURN_LABELS.slice(0, 4), '검수 불합격'],
        4,
        [detail.requestedAt, null, detail.returnShipment?.shippedAt ?? null, detail.pickedUpAt ?? null, detail.processedAt],
      )
    }
    if (detail.status === 'REJECTED') {
      return withStates(['요청', '거절'], 1, [detail.requestedAt, detail.processedAt])
    }
    const current = returnStageIndex(detail)
    return withStates(RETURN_LABELS, current, [
      detail.requestedAt,
      null,
      detail.returnShipment?.shippedAt ?? null,
      detail.pickedUpAt ?? null,
      null,
      current === 5 ? detail.processedAt : null,
    ])
  }

  if (detail.status === 'REJECTED') {
    return withStates(['요청', '거절'], 1, [detail.requestedAt, detail.processedAt])
  }
  const order: ClaimStatus[] = ['REQUESTED', 'APPROVED', 'COMPLETED']
  const currentIndex = order.indexOf(detail.status)
  return withStates(['요청', '승인', '완료'], currentIndex, [
    detail.requestedAt,
    currentIndex === 1 ? detail.processedAt : null,
    currentIndex === 2 ? detail.processedAt : null,
  ])
}
