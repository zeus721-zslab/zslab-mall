import type { SellerSemantic } from '#layers/seller/app/lib/constants/semantic'
import type { SellerRoleCode, SellerStatus } from '#layers/seller/app/types/seller-me'

/** 셀러 상태·역할 라벨 단일 소스(Track 90-B-3·CLAUDE.md 4층위 enum 잠금 (4)프론트). 상단바·정지 배너가 쓴다. */
export const SELLER_STATUS_LABEL: Record<SellerStatus, string> = {
  PENDING: '승인 대기',
  ACTIVE: '정상',
  SUSPENDED: '정지',
  TERMINATED: '종료',
}

export const SELLER_STATUS_SEMANTIC: Record<SellerStatus, SellerSemantic> = {
  PENDING: 'warning',
  ACTIVE: 'success',
  SUSPENDED: 'danger',
  TERMINATED: 'danger',
}

export const SELLER_ROLE_LABEL: Record<SellerRoleCode, string> = {
  SELLER_OWNER: '대표',
  SELLER_MANAGER: '관리자',
  SELLER_STAFF: '담당자',
}
