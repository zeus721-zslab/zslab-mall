import type { AdminClaimAction, AdminClaimActionFilter } from '#layers/admin/app/types/admin-claim'

/**
 * 관리자 클레임 행 액션 라벨(FE-30·BE AdminClaimQueryService availableActions 1:1). 목록 버튼·확인 다이얼로그 제목이 공유한다.
 * 상태·유형 라벨은 `~/lib/constants/claim`(단일 소스)을 그대로 쓴다.
 */
export const ADMIN_CLAIM_ACTION_LABEL: Record<AdminClaimAction, string> = {
  APPROVE: '승인',
  REJECT: '거부',
  CONFIRM_PICKUP: '회수 확인',
  INSPECT: '검수',
  REGISTER_EXCHANGE_SHIPMENT: '교환품 발송',
  MARK_EXCHANGE_DELIVERED: '배송완료',
  INITIATE_REFUND: '환불 개시',
}

/**
 * 목록 "필요 액션" 필터 select 항목(Track 96-4 FE-56·BE AdminClaimActionFilter 1:1). 액션 라벨은 행 버튼 문구(ADMIN_CLAIM_ACTION_LABEL)를
 * 그대로 써서 필터와 버튼이 같은 말을 쓴다. 순서 = 처리 흐름 순.
 */
export const ADMIN_CLAIM_ACTION_FILTER_OPTIONS: { value: AdminClaimActionFilter; title: string }[] = [
  { value: 'FOLLOWUP', title: '후속 처리 전체' },
  { value: 'CONFIRM_PICKUP', title: ADMIN_CLAIM_ACTION_LABEL.CONFIRM_PICKUP },
  { value: 'INSPECT', title: ADMIN_CLAIM_ACTION_LABEL.INSPECT },
  { value: 'REGISTER_EXCHANGE_SHIPMENT', title: ADMIN_CLAIM_ACTION_LABEL.REGISTER_EXCHANGE_SHIPMENT },
  { value: 'MARK_EXCHANGE_DELIVERED', title: ADMIN_CLAIM_ACTION_LABEL.MARK_EXCHANGE_DELIVERED },
  { value: 'INITIATE_REFUND', title: ADMIN_CLAIM_ACTION_LABEL.INITIATE_REFUND },
]

export function isAdminClaimActionFilter(value: string): value is AdminClaimActionFilter {
  return ADMIN_CLAIM_ACTION_FILTER_OPTIONS.some((option) => option.value === value)
}
