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

// ---------- 단계 안내 문구(Track 96-1 FE-53·C-16) ----------

/**
 * 반품 단계별 안내(RETURN_LABELS 인덱스 순). 무엇을 기다리는지·구매자가 할 일만 적고 소요 기간은 적지 않는다(시스템이 보장하지 않음).
 * 승인·회수 확인·검수의 실제 주체는 관리자다(BE AdminClaimController·셀러 API는 조회 전용) — 구매자에게는 주체를 "쇼핑몰"로 부른다(FE-61).
 */
const RETURN_GUIDES: readonly string[] = [
  '쇼핑몰의 승인을 기다리고 있습니다.',
  '반품이 승인되었습니다. 상품을 택배로 발송한 뒤 아래에서 회수 송장을 등록해 주세요.',
  '회수 송장이 등록되었습니다. 쇼핑몰의 회수 확인을 기다리고 있습니다.',
  '회수가 확인되었습니다. 검수 결과를 기다리고 있습니다.',
  '검수가 완료되어 환불을 진행하고 있습니다.',
  '반품과 환불이 완료되었습니다.',
]

/** 교환 단계별 안내(EXCHANGE_LABELS 인덱스 순). */
const EXCHANGE_GUIDES: readonly string[] = [
  '쇼핑몰의 승인을 기다리고 있습니다.',
  '교환이 승인되었습니다. 상품을 택배로 발송한 뒤 아래에서 회수 송장을 등록해 주세요.',
  '회수 송장이 등록되었습니다. 쇼핑몰의 회수 확인을 기다리고 있습니다.',
  '회수가 확인되었습니다. 검수 결과를 기다리고 있습니다.',
  '검수가 완료되었습니다. 교환품 발송을 기다리고 있습니다.',
  '교환품이 발송되었습니다. 배송이 완료되면 교환이 끝납니다.',
  '교환이 완료되었습니다.',
]

/** 취소 상태별 안내. */
const CANCEL_GUIDES: Record<ClaimStatus, string> = {
  REQUESTED: '쇼핑몰의 승인을 기다리고 있습니다.',
  APPROVED: '취소가 승인되어 환불을 진행하고 있습니다.',
  COMPLETED: '취소와 환불이 완료되었습니다.',
  REJECTED: '취소 요청이 거절되었습니다.',
}

/** 현재 단계 1줄 안내. 타임라인과 같은 단계 판정을 쓴다. */
export function claimStageGuide(detail: TimelineSource): string {
  if (detail.claimType === 'EXCHANGE') {
    if (detail.status === 'REJECTED') {
      return detail.inspectionResult === 'FAIL' ? '검수 결과 교환이 거절되어 상품을 다시 보내드립니다.' : '교환 요청이 거절되었습니다.'
    }
    return EXCHANGE_GUIDES[exchangeStageIndex(detail)] ?? ''
  }
  if (detail.claimType === 'RETURN') {
    if (detail.status === 'REJECTED') {
      return detail.inspectionResult === 'FAIL' ? '검수 결과 반품이 거절되어 상품을 다시 보내드립니다.' : '반품 요청이 거절되었습니다.'
    }
    return RETURN_GUIDES[returnStageIndex(detail)] ?? ''
  }
  return CANCEL_GUIDES[detail.status]
}
