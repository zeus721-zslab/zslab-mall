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

// ---------- 정산계좌(FE-41·Track 89-F D-188) ----------

/** BE SellerBankAccountStatus 3값(seller_bank_account.status ENUM). 관리자 등록은 항상 VERIFIED(실명인증 연동은 이월). */
export type AdminSellerBankAccountStatus = 'PENDING' | 'VERIFIED' | 'REJECTED'

export const ADMIN_SELLER_BANK_ACCOUNT_STATUS_LABEL: Record<AdminSellerBankAccountStatus, string> = {
  PENDING: '인증 대기',
  VERIFIED: '인증 완료',
  REJECTED: '거부',
}

/**
 * 은행 선택 옵션(bank_code VARCHAR(20)·BE는 자유 문자열·시드 KB/SHINHAN/WOORI와 같은 영문 약칭). 목록에 없는 코드는 화면이 코드 그대로 표기한다
 * (bankLabel). 실명인증 연동 시 금융결제원 표준 코드로 바꿀 수 있도록 code를 값으로 둔다.
 */
export const ADMIN_BANK_OPTIONS: { value: string; title: string }[] = [
  { value: 'KB', title: 'KB국민은행' },
  { value: 'SHINHAN', title: '신한은행' },
  { value: 'WOORI', title: '우리은행' },
  { value: 'HANA', title: '하나은행' },
  { value: 'NH', title: 'NH농협은행' },
  { value: 'IBK', title: 'IBK기업은행' },
  { value: 'SC', title: 'SC제일은행' },
  { value: 'CITI', title: '씨티은행' },
  { value: 'POST', title: '우체국' },
  { value: 'KAKAO', title: '카카오뱅크' },
  { value: 'TOSS', title: '토스뱅크' },
  { value: 'KBANK', title: '케이뱅크' },
  { value: 'BUSAN', title: '부산은행' },
  { value: 'DAEGU', title: 'iM뱅크(대구은행)' },
  { value: 'GWANGJU', title: '광주은행' },
  { value: 'JEONBUK', title: '전북은행' },
  { value: 'KYONGNAM', title: '경남은행' },
  { value: 'JEJU', title: '제주은행' },
  { value: 'SUHYUP', title: '수협은행' },
  { value: 'SAEMAUL', title: '새마을금고' },
  { value: 'SHINHYUP', title: '신협' },
]

/** BE AdminSellerBankAccountRegisterRequest·UpdateRequest @Size·@Pattern(계좌번호 숫자·하이픈 6~30자·bank_code 20·account_holder 50). */
export const ADMIN_SELLER_BANK_CODE_MAX = 20
export const ADMIN_SELLER_ACCOUNT_NUMBER_MIN = 6
export const ADMIN_SELLER_ACCOUNT_NUMBER_MAX = 30
export const ADMIN_SELLER_ACCOUNT_HOLDER_MAX = 50
export const ADMIN_SELLER_ACCOUNT_NUMBER_PATTERN = /^[0-9-]+$/

/** 정산 지급에 사용된 계좌(referencedBySettlement) 수정 불가 툴팁·안내(외부 검토 Q6·지적 13·계좌 단위 의미). */
export const SELLER_BANK_ACCOUNT_REFERENCED_TOOLTIP = '정산 지급에 사용된 계좌입니다. 새 계좌를 등록한 뒤 주 계좌로 전환하세요.'
export const SELLER_BANK_ACCOUNT_REFERENCED_NOTE = '정산 지급에 사용된 계좌는 수정할 수 없습니다. 수정이 필요하면 새 계좌를 등록한 뒤 주 계좌로 전환하세요.'

/** 첫 계좌 자동 주 계좌 안내(D-188 결정 9). */
export const SELLER_BANK_ACCOUNT_FIRST_PRIMARY_NOTICE = '첫 번째 계좌는 자동으로 주 정산계좌가 됩니다. 등록 즉시 정산 지급이 가능해집니다.'

/**
 * 주 계좌 전환 안내(D-188·D-179 결정 7: 지급 시점에 주 계좌를 재조회해 스냅샷). 지급되지 않은 정산(대기·확정)은 전환된 계좌로 지급되고,
 * 이미 지급완료된 정산은 지급 당시 계좌 id가 기록돼 있어 영향이 없다.
 */
export const SELLER_BANK_ACCOUNT_PRIMARY_CHANGE_NOTICE = [
  '이후 정산 지급은 이 계좌로 이루어집니다.',
  '아직 지급되지 않은 정산(지급 대기·확정)도 지급 시점의 주 계좌인 이 계좌로 지급됩니다.',
  '이미 지급완료된 정산은 지급 당시 계좌가 기록되어 있어 영향이 없습니다.',
] as const
