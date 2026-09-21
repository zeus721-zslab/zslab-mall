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
 * 은행 옵션·계좌 형식 한도는 Track 90-D-3(FE-51)에서 공용 app/lib/constants/bank.ts 로 이동했다(셀러 본인 등록 폼과 단일 소스).
 * 관리자 코드·spec의 기존 이름을 유지하기 위해 여기서 re-export만 한다.
 */
export {
  BANK_OPTIONS as ADMIN_BANK_OPTIONS,
  BANK_CODE_MAX as ADMIN_SELLER_BANK_CODE_MAX,
  ACCOUNT_NUMBER_MIN as ADMIN_SELLER_ACCOUNT_NUMBER_MIN,
  ACCOUNT_NUMBER_MAX as ADMIN_SELLER_ACCOUNT_NUMBER_MAX,
  ACCOUNT_HOLDER_MAX as ADMIN_SELLER_ACCOUNT_HOLDER_MAX,
  ACCOUNT_NUMBER_PATTERN as ADMIN_SELLER_ACCOUNT_NUMBER_PATTERN,
} from '~/lib/constants/bank'

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

// ---------- 구성원(FE-42·Track 89-G D-189) ----------

/** BE RoleCode 중 셀러 구성원 역할 3값(seller_user.role_id → role.code·V11 시드). 4층위 (4)프론트 단일 소스. */
export type AdminSellerMemberRole = 'SELLER_OWNER' | 'SELLER_MANAGER' | 'SELLER_STAFF'

export const ADMIN_SELLER_MEMBER_ROLES: AdminSellerMemberRole[] = ['SELLER_OWNER', 'SELLER_MANAGER', 'SELLER_STAFF']

export const ADMIN_SELLER_MEMBER_ROLE_LABEL: Record<AdminSellerMemberRole, string> = {
  SELLER_OWNER: '대표',
  SELLER_MANAGER: '매니저',
  SELLER_STAFF: '담당자',
}

/** 역할 배지 톤: 대표 info(정산 SMS 대체 수신처·가드 대상) / 매니저·담당자 neutral. */
export const ADMIN_SELLER_MEMBER_ROLE_TONE: Record<AdminSellerMemberRole, AdminSellerStatusTone> = {
  SELLER_OWNER: 'info',
  SELLER_MANAGER: 'neutral',
  SELLER_STAFF: 'neutral',
}

export const ADMIN_SELLER_MEMBER_ROLE_OPTIONS: { value: AdminSellerMemberRole; title: string }[] =
  ADMIN_SELLER_MEMBER_ROLES.map((value) => ({ value, title: ADMIN_SELLER_MEMBER_ROLE_LABEL[value] }))

/** BE AdminSellerMemberNewUserRequest @Size·@Pattern과 동일(User 컬럼 SoT·이메일 정규식은 SignupRequest와 동일). 휴대폰 형식은 BE에 없고 FE만 ADMIN_MEMBER_PHONE_PATTERN으로 더 엄격(SMS 수신처·admin-seller-member-view). */
export const ADMIN_SELLER_MEMBER_EMAIL_MAX = 254
export const ADMIN_SELLER_MEMBER_NAME_MAX = 50
export const ADMIN_SELLER_MEMBER_PHONE_MAX = 20
export const ADMIN_SELLER_MEMBER_EMAIL_PATTERN = /^[^@\s]+@[^@\s]+\.[^@\s]+$/

/**
 * 역할이 권한에 영향을 주지 않는다는 안내(D-189 §1-A 2: 역할은 데이터만 유지·권한 분기 없음·셀러 API 16개 전부 셀러 단위 판정). 운영자가 "담당자로
 * 주면 제한될 것"이라 오해하지 않도록 추가·역할 변경 다이얼로그 모두에 표시한다.
 */
export const SELLER_MEMBER_ROLE_NOTICE = [
  '역할은 구분·표시용입니다. 현재 모든 구성원이 같은 셀러 기능(상품·송장·클레임·정산 조회)을 사용하며, 역할에 따라 제한되지 않습니다.',
  '대표(OWNER)는 셀러 연락처가 없을 때 정산 안내 SMS의 대체 수신처가 되며, 마지막 활성 대표는 제거·강등할 수 없습니다.',
] as const

/** 새 계정 생성 경로 안내(D-189 §1-A 3: BUYER 겸직 기본·임시 비밀번호 SMS·변경 강제·SMS 실패 시 전체 롤백). */
export const SELLER_MEMBER_NEW_USER_NOTICE = [
  '입력한 이메일로 새 회원 계정이 만들어지고, 일반 회원(구매자) 자격도 함께 부여됩니다. 이 계정은 회원 목록에도 표시됩니다.',
  '임시 비밀번호는 입력한 휴대폰으로 SMS 발송됩니다. 화면에는 표시되지 않으며, 첫 로그인 후 비밀번호를 변경해야 합니다.',
  'SMS 발송에 실패하면 계정은 만들어지지 않습니다(계정·구성원 등록이 함께 취소됩니다).',
] as const

/** 구성원 제거 확인 안내(D-189 §1-A 1: 리졸버 매 요청 조회 → 즉시 차단·BUYER 세션·계정 유지). */
export const SELLER_MEMBER_REMOVE_NOTICE = [
  '제거 즉시 이 계정의 셀러 로그인과 셀러 기능 접근이 차단됩니다(이미 로그인한 세션도 다음 요청부터 차단).',
  '일반 회원(구매자) 계정·주문 이력은 그대로 유지되며, 필요하면 다시 구성원으로 추가할 수 있습니다.',
] as const

/** 마지막 활성 대표 제거·강등 불가 툴팁(BE 409 SELLER_LAST_OWNER와 같은 판정·D-189 §1-A 2). */
export const SELLER_MEMBER_LAST_OWNER_TOOLTIP = '마지막 활성 대표(OWNER)는 제거·강등할 수 없습니다. 다른 구성원을 대표로 먼저 추가하거나 지정하세요.'

/** 구성원 0명이 정상 상태임을 알리는 안내(셀러 1이 실제로 구성원 0으로 운영 중·관리자 주도 흐름은 구성원을 읽지 않음). */
export const SELLER_MEMBERS_EMPTY_NOTE = '구성원이 없어도 셀러는 정상 운영됩니다(상품·주문·정산은 관리자가 대신 처리). 셀러가 직접 로그인해 처리하려면 구성원을 추가하세요.'
