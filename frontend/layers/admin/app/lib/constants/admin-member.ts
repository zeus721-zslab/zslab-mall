/**
 * 관리자 회원 관리 상수 단일 소스(Track 84 FE·CLAUDE.md 4층위 enum 잠금 (4)프론트). BE D-178 계약(AdminMemberStatusFilter·AdminMemberSort·
 * BuyerGradeCode·GradeSource)과 1:1이며 관리자 회원 목록·상세·다이얼로그의 유일한 출처다.
 */

/** BE AdminMemberStatusFilter 2값(withdrawn_at NULL 여부). 페이지가 고정 주입하며 URL query에는 싣지 않는다. */
export type AdminMemberStatus = 'ACTIVE' | 'WITHDRAWN'

/** BE AdminMemberSort 2값(가입일 기준). */
export type AdminMemberSort = 'LATEST' | 'OLDEST'

export const ADMIN_MEMBER_SORT_OPTIONS: { value: AdminMemberSort; title: string }[] = [
  { value: 'LATEST', title: '최근 가입순' },
  { value: 'OLDEST', title: '오래된 가입순' },
]
export const DEFAULT_ADMIN_MEMBER_SORT: AdminMemberSort = 'LATEST'

/** BE 검색어 상한(AdminMemberQueryService MAX_KEYWORD_LENGTH=50). */
export const ADMIN_MEMBER_KEYWORD_MAX = 50
export const ADMIN_MEMBER_PAGE_SIZES: number[] = [20, 50, 100]
export const DEFAULT_ADMIN_MEMBER_PAGE_SIZE = 20

/** BE BuyerGradeCode 3값(buyer_grade.code ENUM). */
export type AdminBuyerGradeCode = 'SILVER' | 'GOLD' | 'PLATINUM'

export const ADMIN_BUYER_GRADE_LABEL: Record<AdminBuyerGradeCode, string> = {
  SILVER: '실버',
  GOLD: '골드',
  PLATINUM: '플래티넘',
}

export const ADMIN_BUYER_GRADE_OPTIONS: { value: AdminBuyerGradeCode; title: string }[] = (
  ['SILVER', 'GOLD', 'PLATINUM'] as AdminBuyerGradeCode[]
).map((value) => ({ value, title: ADMIN_BUYER_GRADE_LABEL[value] }))

/** BE GradeSource 3값(buyer_profile.grade_source ENUM). */
export type AdminGradeSource = 'AUTO' | 'MANUAL' | 'EVENT'

export const ADMIN_GRADE_SOURCE_LABEL: Record<AdminGradeSource, string> = {
  AUTO: '자동',
  MANUAL: '수동',
  EVENT: '이벤트',
}

/** 회원 수정 phone 형식(BE AdminMemberUpdateRequest.PHONE_PATTERN과 동일·국내 휴대폰·하이픈 선택). */
export const ADMIN_MEMBER_PHONE_PATTERN = /^01[016789]-?\d{3,4}-?\d{4}$/
export const ADMIN_MEMBER_NAME_MAX = 50
export const ADMIN_MEMBER_PHONE_MAX = 20

/** 회원 상세 주문정보 탭. orders는 주문 목록, 나머지는 클레임 목록 type 필터와 1:1. */
export type AdminMemberActivityTab = 'orders' | 'cancel' | 'return' | 'exchange'

export const ADMIN_MEMBER_ACTIVITY_TABS: { value: AdminMemberActivityTab; label: string }[] = [
  { value: 'orders', label: '주문' },
  { value: 'cancel', label: '취소' },
  { value: 'return', label: '반품' },
  { value: 'exchange', label: '교환' },
]
export const DEFAULT_ADMIN_MEMBER_ACTIVITY_TAB: AdminMemberActivityTab = 'orders'
