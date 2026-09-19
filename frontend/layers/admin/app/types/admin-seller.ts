import type { AdminSellerStatus, AdminSellerTerminationBlockCode } from '#layers/admin/app/lib/constants/admin-seller'
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

/** 소속 구성원(seller_user). 탈퇴 회원은 withdrawnAt, soft-delete 회원은 user 필드 전부 생략(userPublicId 포함). */
export interface AdminSellerMember {
  userPublicId?: string
  email?: string
  name?: string
  roleCode?: 'SELLER_OWNER' | 'SELLER_MANAGER' | 'SELLER_STAFF'
  withdrawnAt?: string
}

/** 현재 주 정산계좌(끝 4자리만). */
export interface AdminSellerBankAccount {
  id: number
  bankCode: string
  accountHolder: string
  accountNumberSuffix: string
  status: 'PENDING' | 'VERIFIED' | 'REJECTED'
  verifiedAt?: string
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

/** POST /admin/sellers 본문(BE SellerProvisioningRequest·owner는 회원 public_id). */
export interface AdminSellerProvisionRequest {
  companyName: string
  businessNo: string | null
  ceoName: string
  contactEmail: string | null
  contactPhone: string | null
  status: 'ACTIVE' | 'PENDING'
  ownerUserPublicId: string
}
