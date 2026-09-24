import type { ClaimType } from '~/lib/constants/claim'

/**
 * renew 클레임 유형 색 규칙(FE-73 보완 1·한 곳). 유형 배지는 기존 파스텔 토큰으로 채우고(새 색 값 없음),
 * 상태 칩·환불 상태·거부 사유는 흰 바탕 + 테두리의 중립 칩으로 둔다 — 색은 "무슨 요청인지"에만 쓴다.
 * 쓰는 곳: 클레임 탭 카드 · 주문 카드의 진행 클레임 배지 · 클레임 상세 헤더 · 클레임 신청 화면.
 */
export const CLAIM_TYPE_BADGE_CLASS: Record<ClaimType, string> = {
  CANCEL: 'bg-(--pastel-butter-bg) text-(--pastel-butter-ink)',
  RETURN: 'bg-(--pastel-pink-bg) text-(--pastel-pink-ink)',
  EXCHANGE: 'bg-(--pastel-periwinkle-bg) text-(--pastel-periwinkle-ink)',
}

/** 상태 칩·환불 상태·거부 사유(중립). */
export const CLAIM_NEUTRAL_CHIP_CLASS = 'border border-line bg-white text-ink'
