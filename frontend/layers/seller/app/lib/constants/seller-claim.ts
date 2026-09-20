import type { ClaimStatus, ClaimType } from '~/lib/constants/claim'

/**
 * 셀러 클레임 상수 단일 소스(Track 90-D-1·CLAUDE.md 4층위 enum 잠금 (4)프론트). 유형·상태·사유·환불 라벨은 사용자 FE 상수(~/lib/constants/claim)를
 * 그대로 쓰고, chip 의미 색상은 seller-order(SELLER_CLAIM_STATUS_SEMANTIC)를 재사용한다. 여기서는 필터 옵션·페이지 크기·검색어 한도만 정의한다.
 */

/** 유형 필터 옵션(BE ClaimType 3값·정의 순서). */
export const SELLER_CLAIM_TYPE_CODES: ClaimType[] = ['CANCEL', 'RETURN', 'EXCHANGE']

/** 상태 필터 옵션(BE ClaimStatus 4값·전이 순서). */
export const SELLER_CLAIM_STATUS_CODES: ClaimStatus[] = ['REQUESTED', 'APPROVED', 'REJECTED', 'COMPLETED']

/** 페이지 크기 옵션(BE size 1~100 클램프·주문 목록과 동일 3단). */
export const SELLER_CLAIM_PAGE_SIZES: number[] = [20, 50, 100]
export const DEFAULT_SELLER_CLAIM_PAGE_SIZE = 20

/** 검색어 최대 길이(BE SellerClaimQueryService MAX_KEYWORD_LENGTH). */
export const SELLER_CLAIM_KEYWORD_MAX = 50
