import type { AdminClaimAction } from '#layers/admin/app/types/admin-claim'

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
}
