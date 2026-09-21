import type {
  AdminSellerBankAccountStatus,
  AdminSellerMemberRole,
  AdminSellerStatus,
  AdminSellerTerminationBlockCode,
} from '#layers/admin/app/lib/constants/admin-seller'
import type { AdminProductStatus } from '#layers/admin/app/lib/constants/product'
import type { AdminSettlementStatus } from '#layers/admin/app/lib/constants/admin-settlement'

/**
 * 관리자 셀러 API 타입(FE-40·D-187 BE 계약 1:1). nullable 필드는 BE NON_NULL 직렬화로 생략될 수 있어 optional.
 * 시각 문자열은 오프셋 없는 LocalDateTime이며 formatDateTime으로만 표시한다.
 */

/** 목록 행(BE AdminSellerSummaryResponse). productCount는 활성(미삭제) 상품 수. */
export interface AdminSellerListItem {
  sellerPublicId: string
  companyName: string
  businessNo?: string
  ceoName: string
  contactEmail?: string
  contactPhone?: string
  status: AdminSellerStatus
  productCount: number
  hasPrimaryBankAccount: boolean
  createdAt: string
}

/** 페이징 봉투(BE PagedResponse·필드 5개). */
export interface AdminSellerListResponse {
  items: AdminSellerListItem[]
  page: number
  size: number
  totalCount: number
  hasNext: boolean
}

/**
 * 소속 구성원(seller_user·BE AdminSellerDetailResponse.Member). 탈퇴 회원은 withdrawnAt, soft-delete 회원은 user 필드 전부 생략(userPublicId 포함).
 * joinedAt = 구성원 등록 시각(FE-42·D-189). 추가 API(POST 201)의 응답도 같은 형태다.
 */
export interface AdminSellerMember {
  userPublicId?: string
  email?: string
  name?: string
  roleCode?: AdminSellerMemberRole
  withdrawnAt?: string
  joinedAt: string
}

/** POST /admin/sellers/{slr_}/members 201 응답(BE AdminSellerMemberAddResponse·D-204). 신규 계정 생성 시에만 temporaryPassword(기존 회원 연결은 생략). */
export interface AdminSellerMemberAddResponse extends AdminSellerMember {
  temporaryPassword?: string
}

/** POST /admin/sellers/{slr_}/members 신규 계정 정보(BE AdminSellerMemberNewUserRequest·셀프 가입과 같은 필드·phone은 임시 비밀번호 SMS 수신처). */
export interface AdminSellerMemberNewUser {
  email: string
  name: string
  phone: string
}

/** POST /admin/sellers/{slr_}/members 본문(BE AdminSellerMemberAddRequest·userPublicId XOR newUser·사유 없음). */
export interface AdminSellerMemberAddRequest {
  userPublicId?: string
  newUser?: AdminSellerMemberNewUser
  role: AdminSellerMemberRole
}

/** DELETE /admin/sellers/{slr_}/members/{usr_} 본문(사유 필수). */
export interface AdminSellerMemberRemoveRequest {
  reason: string
}

/** PATCH /admin/sellers/{slr_}/members/{usr_}/role 본문(사유 필수·같은 역할 422). */
export interface AdminSellerMemberRoleChangeRequest {
  role: AdminSellerMemberRole
  reason: string
}

/** 현재 주 정산계좌(끝 4자리만·BE AdminSellerDetailResponse.BankAccount). 전체 계좌번호는 어떤 응답에도 없다. */
export interface AdminSellerBankAccount {
  id: number
  bankCode: string
  accountHolder: string
  accountNumberSuffix: string
  status: AdminSellerBankAccountStatus
  verifiedAt?: string
}

/**
 * 계좌 목록 행(FE-41·BE AdminSellerBankAccountResponse·D-188). 등록순. referencedBySettlement = 정산이 지급 계좌로 참조(외부 검토 Q6 미리보기·
 * BE 수정 409와 같은 판정) → 수정 불가.
 */
export interface AdminSellerBankAccountRow extends AdminSellerBankAccount {
  isPrimary: boolean
  referencedBySettlement: boolean
  createdAt: string
  updatedAt: string
}

export interface AdminSellerSettlementTotal {
  status: AdminSettlementStatus
  count: number
  netAmount: number
}

/** 종료 차단 사유 1건(BE SellerTerminationBlock). 상세 미리보기와 409 blocks가 같은 형태. */
export interface AdminSellerTerminationBlock {
  code: AdminSellerTerminationBlockCode
  count: number
}

export interface AdminSellerWarnings {
  primaryBankAccountMissing: boolean
  saleProductCount: number
}

/** 상세(BE AdminSellerDetailResponse). 전이 응답(PATCH status 200)도 같은 형태다. */
export interface AdminSellerDetail {
  sellerPublicId: string
  companyName: string
  businessNo?: string
  ceoName: string
  contactEmail?: string
  contactPhone?: string
  status: AdminSellerStatus
  /** basis-point(1000 = 10.00%). 미설정(개별 계약 없음)이면 생략. */
  commissionRate?: number
  createdAt: string
  updatedAt: string
  members: AdminSellerMember[]
  primaryBankAccount?: AdminSellerBankAccount
  /** 계좌 전부(등록순·FE-41). 없으면 빈 배열. */
  bankAccounts: AdminSellerBankAccountRow[]
  productCount: number
  productCountByStatus: Partial<Record<AdminProductStatus, number>>
  orderCount: number
  confirmedSalesAmount: number
  settlements: AdminSellerSettlementTotal[]
  terminable: boolean
  terminationBlocks: AdminSellerTerminationBlock[]
  warnings: AdminSellerWarnings
}

/** 목록 화면 상태(URL query 단일 소스). */
export interface AdminSellerListQuery {
  status: AdminSellerStatus | null
  keyword: string
  page: number
  size: number
}

/** BE GET /admin/sellers/page 파라미터. */
export interface AdminSellerApiParams {
  status?: AdminSellerStatus
  keyword?: string
  page: number
  size: number
}

/** PATCH /admin/sellers/{slr_}/status 본문(BE AdminSellerStatusChangeRequest·PENDING 목표 불가). */
export interface AdminSellerStatusChangeRequest {
  status: Exclude<AdminSellerStatus, 'PENDING'>
  reason: string
}

/** PUT /admin/sellers/{slr_} 본문(BE AdminSellerUpdateRequest·전체 필드·reason은 율 변경 시 필수). */
export interface AdminSellerUpdateRequest {
  companyName: string
  businessNo: string | null
  ceoName: string
  contactEmail: string | null
  contactPhone: string | null
  commissionRate: number | null
  reason: string | null
}

/** POST /admin/sellers/{slr_}/bank-accounts 본문(BE AdminSellerBankAccountRegisterRequest·최초 등록은 사유 없음). */
export interface AdminSellerBankAccountRegisterRequest {
  bankCode: string
  accountNumber: string
  accountHolder: string
}

/** PUT /admin/sellers/{slr_}/bank-accounts/{id} 본문(전체 필드·사유 필수·정산 참조 행은 409). */
export interface AdminSellerBankAccountUpdateRequest extends AdminSellerBankAccountRegisterRequest {
  reason: string
}

/** PATCH /admin/sellers/{slr_}/bank-accounts/{id}/primary 본문(사유 필수). */
export interface AdminSellerBankAccountPrimaryRequest {
  reason: string
}

/** POST /admin/sellers 본문(BE SellerProvisioningRequest·owner는 회원 public_id·null이면 구성원 없이 입점·FE-42 D-189). */
export interface AdminSellerProvisionRequest {
  companyName: string
  businessNo: string | null
  ceoName: string
  contactEmail: string | null
  contactPhone: string | null
  status: 'ACTIVE' | 'PENDING'
  ownerUserPublicId: string | null
}
