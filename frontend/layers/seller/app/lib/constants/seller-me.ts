import type { SellerSemantic } from '#layers/seller/app/lib/constants/semantic'
import type { SellerRoleCode, SellerStatus } from '#layers/seller/app/types/seller-me'
import { SELLER_MEMBER_ROLE_LABELS, SELLER_STATUS_LABELS } from '~/lib/constants/seller'

/** 셀러 상태·역할 라벨 단일 소스(Track 90-B-3·CLAUDE.md 4층위 enum 잠금 (4)프론트). 상단바·정지 배너가 쓴다. */
/** 라벨 실체는 공용 단일 소스(app/lib/constants/seller.ts·Track 102 FE-64). 셀러 코드의 기존 이름만 유지한다. */
export const SELLER_STATUS_LABEL: Record<SellerStatus, string> = SELLER_STATUS_LABELS

export const SELLER_STATUS_SEMANTIC: Record<SellerStatus, SellerSemantic> = {
  PENDING: 'warning',
  ACTIVE: 'success',
  SUSPENDED: 'danger',
  TERMINATED: 'danger',
}

export const SELLER_ROLE_LABEL: Record<SellerRoleCode, string> = SELLER_MEMBER_ROLE_LABELS
