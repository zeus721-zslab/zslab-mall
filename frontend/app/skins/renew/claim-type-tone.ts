import type { ClaimType } from '~/lib/constants/claim'
import type { RenewBadgeTone } from './components/RenewBadge.vue'

/**
 * renew 클레임 유형 색 규칙(FE-73 보완 1·한 곳). 유형 배지는 RenewBadge로 채우고(FE-80 — 색은 기존 파스텔 그대로: 취소 butter·반품 pink·
 * 교환 periwinkle), 상태 칩·환불 상태·거부 사유는 흰 바탕 + 테두리의 중립 칩으로 둔다 — 색은 "무슨 요청인지"에만 쓴다.
 * 쓰는 곳: 클레임 탭 카드 · 주문 카드의 진행 클레임 배지 · 클레임 상세 헤더 · 클레임 신청 화면.
 */
export const CLAIM_TYPE_BADGE_TONE: Record<ClaimType, RenewBadgeTone> = {
  CANCEL: 'warning',
  RETURN: 'danger',
  EXCHANGE: 'neutral',
}

/** 상태 칩·환불 상태·거부 사유(중립). 크기는 RenewBadge와 같다(22px · 12/600 · FE-80). */
export const CLAIM_NEUTRAL_CHIP_CLASS =
  'inline-flex h-[22px] shrink-0 items-center whitespace-nowrap rounded-full border border-line bg-white px-2.5 text-xs font-semibold leading-none text-ink'
