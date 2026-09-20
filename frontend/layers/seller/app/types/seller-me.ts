/**
 * 셀러 본인 조회 API 타입(Track 90-B-3·D-191 `GET /api/v1/seller/me` 응답 1:1·backend seller/controller/response/SellerMeResponse 실측).
 * status는 BE SellerStatus 4값이나 PENDING·TERMINATED는 resolver가 401로 막아 응답에 실리지 않는다(ACTIVE·SUSPENDED만 도달).
 */
export type SellerStatus = 'PENDING' | 'ACTIVE' | 'SUSPENDED' | 'TERMINATED'

/** 셀러 내 역할 코드(BE RoleCode·SELLER_OWNER/SELLER_MANAGER/SELLER_STAFF). */
export type SellerRoleCode = 'SELLER_OWNER' | 'SELLER_MANAGER' | 'SELLER_STAFF'

export interface SellerMe {
  sellerPublicId: string
  companyName: string
  status: SellerStatus
  roleCode: SellerRoleCode
  /** 정산 예정 건수(PENDING count·금액 아님·D-191 ε). */
  pendingSettlementCount: number
  bankAccountRegistered: boolean
}
