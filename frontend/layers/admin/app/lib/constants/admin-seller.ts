import type { AdminSemantic } from '#layers/admin/app/lib/constants/semantic'

/**
 * 관리자 셀러 관리 상수 단일 소스(FE-40·Track 89-D D-187·CLAUDE.md 4층위 enum 잠금 (4)프론트). BE SellerStatus·SellerTerminationBlockCode·
 * 전이 매트릭스(state-machine.md §7)와 1:1이며 셀러 목록·상세·다이얼로그·셀러 선택 드롭다운(types/admin-product.ts)의 유일한 출처다.
 */

/** BE SellerStatus 4값(seller.status ENUM). */
export type AdminSellerStatus = 'PENDING' | 'ACTIVE' | 'SUSPENDED' | 'TERMINATED'

export const ADMIN_SELLER_STATUSES: AdminSellerStatus[] = ['PENDING', 'ACTIVE', 'SUSPENDED', 'TERMINATED']

export const ADMIN_SELLER_STATUS_LABEL: Record<AdminSellerStatus, string> = {
  PENDING: '승인 대기',
  ACTIVE: '활성',
  SUSPENDED: '정지',
  TERMINATED: '종료',
}

/** 배지 톤(admin-vuetify.css .adm-chip--*): 활성 녹색 / 승인 대기 노랑(warning) / 정지 주황·적색(danger·구매 차단 의미) / 종료 회색(neutral). */
export type AdminSellerStatusTone = AdminSemantic | 'neutral'

export const ADMIN_SELLER_STATUS_TONE: Record<AdminSellerStatus, AdminSellerStatusTone> = {
  PENDING: 'warning',
  ACTIVE: 'success',
  SUSPENDED: 'danger',
  TERMINATED: 'neutral',
}

export const ADMIN_SELLER_STATUS_OPTIONS: { value: AdminSellerStatus | null; title: string }[] = [
  { value: null, title: '전체 상태' },
  ...ADMIN_SELLER_STATUSES.map((value) => ({ value, title: ADMIN_SELLER_STATUS_LABEL[value] })),
]

/**
 * 전이 매트릭스(BE SellerStatus.canTransitionTo·state-machine.md §7). 화면은 현재 상태에서 가능한 목표만 버튼으로 노출하고
 * 실제 판정은 BE 422가 SoT다. TERMINATED는 불가역(빈 배열).
 */
export const ADMIN_SELLER_TRANSITIONS: Record<AdminSellerStatus, AdminSellerStatus[]> = {
  PENDING: ['ACTIVE', 'TERMINATED'],
  ACTIVE: ['SUSPENDED', 'TERMINATED'],
  SUSPENDED: ['ACTIVE', 'TERMINATED'],
  TERMINATED: [],
}

/** 전이 버튼 라벨(목표 상태 × 현재 상태). */
export const ADMIN_SELLER_TRANSITION_LABEL: Record<Exclude<AdminSellerStatus, 'PENDING'>, string> = {
  ACTIVE: '활성화',
  SUSPENDED: '정지',
  TERMINATED: '종료',
}

/** BE SellerTerminationBlockCode 3값(종료 가드 G1~G3). */
export type AdminSellerTerminationBlockCode = 'UNPAID_SETTLEMENT' | 'ORDER_ITEM_IN_PROGRESS' | 'CLAIM_ACTIVE'

export const ADMIN_SELLER_TERMINATION_BLOCK_LABEL: Record<AdminSellerTerminationBlockCode, string> = {
  UNPAID_SETTLEMENT: '미지급 정산',
  ORDER_ITEM_IN_PROGRESS: '진행 중 주문',
  CLAIM_ACTIVE: '처리 중 클레임',
}

/** 입점 시 선택 가능한 초기 상태(BE SellerProvisioningService.validateInitialStatus: PENDING·ACTIVE만). */
export const ADMIN_SELLER_INITIAL_STATUS_OPTIONS: { value: 'ACTIVE' | 'PENDING'; title: string }[] = [
  { value: 'ACTIVE', title: '즉시 활성(바로 판매 가능)' },
  { value: 'PENDING', title: '승인 대기(검토 후 활성화)' },
]

/** BE 검색어 상한(AdminSellerQueryService MAX_KEYWORD_LENGTH=50·상호·사업자번호·담당자 이메일). */
export const ADMIN_SELLER_KEYWORD_MAX = 50
export const ADMIN_SELLER_PAGE_SIZES: number[] = [20, 50, 100]
export const DEFAULT_ADMIN_SELLER_PAGE_SIZE = 20

/** 전이·수정 사유 상한(BE AdminSellerStatusChangeRequest·AdminSellerUpdateRequest reason @Size 200). */
export const ADMIN_SELLER_REASON_MAX = 200

/** BE Seller 컬럼 길이(SellerProvisioningRequest·AdminSellerUpdateRequest @Size). */
export const ADMIN_SELLER_COMPANY_NAME_MAX = 100
export const ADMIN_SELLER_BUSINESS_NO_MAX = 20
export const ADMIN_SELLER_CEO_NAME_MAX = 50
export const ADMIN_SELLER_CONTACT_EMAIL_MAX = 254
export const ADMIN_SELLER_CONTACT_PHONE_MAX = 20

/** 입점 등록 다이얼로그의 회원(owner) 검색 결과 상한(회원 목록 API size·FE-39 동일). */
export const ADMIN_SELLER_MEMBER_SEARCH_SIZE = 10

/**
 * 셀러 수수료율 변경 경고(D-187 §1-A 8·D-179 결정 2). 율은 주문 생성 시 order_item에 스냅샷되며 셀러 개별율은 3단 판정(셀러 → 카테고리 → 플랫폼 기본율)의
 * 최우선이다.
 */
export const SELLER_COMMISSION_RATE_CHANGE_WARNING = [
  '수수료율은 변경 시점 이후 새로 생성되는 주문부터 적용됩니다.',
  '이미 생성된 주문·정산(재생성 포함)에는 영향이 없습니다.',
  '셀러 개별 수수료율은 카테고리 수수료율보다 우선 적용됩니다(미설정이면 카테고리율 → 플랫폼 기본율).',
] as const

/** 종료(TERMINATED) 불가역 안내 — 확인 다이얼로그 본문에 반드시 포함한다. */
export const SELLER_TERMINATE_IRREVERSIBLE_NOTICE = '종료 후에는 어떤 상태로도 되돌릴 수 없습니다.'
