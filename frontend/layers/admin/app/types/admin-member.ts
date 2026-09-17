import type { AdminBuyerGradeCode, AdminGradeSource, AdminMemberSort } from '#layers/admin/app/lib/constants/admin-member'

/**
 * 관리자 회원 API 타입(Track 84 FE·D-178 BE 계약 1:1). nullable 필드는 BE NON_NULL 직렬화로 생략될 수 있어 optional.
 * 시각 문자열은 오프셋 없는 LocalDateTime(예: 2026-09-16T10:00:00)이며 formatDateTime(앞 16자 슬라이스)으로만 표시한다.
 */

/** 목록 행(BE AdminMemberSummaryResponse). */
export interface AdminMemberSummary {
  publicId: string
  name?: string
  email?: string
  phone?: string
  gradeCode?: AdminBuyerGradeCode
  createdAt: string
  /** 결제 완료 주문의 최근 paid_at. 없으면 생략. */
  lastPaidAt?: string
  withdrawnAt?: string
}

/** 페이징 봉투(BE PagedResponse·필드 5개). */
export interface AdminMemberListResponse {
  items: AdminMemberSummary[]
  page: number
  size: number
  totalCount: number
  hasNext: boolean
}

/** 상세 등급(buyer_profile). */
export interface AdminMemberGrade {
  code?: AdminBuyerGradeCode
  source: AdminGradeSource
  lockedUntil?: string
}

/** 상세 배송지(user_address·구매자 AddressResponse와 동형). */
export interface AdminMemberAddress {
  id: number
  isDefault: boolean
  addressLabel?: string
  recipientName: string
  recipientPhone: string
  zonecode: string
  addressRoad: string
  addressJibun?: string
  addressDetail?: string
}

/** 상세(BE AdminMemberDetailResponse). */
export interface AdminMemberDetail {
  publicId: string
  name?: string
  email?: string
  phone?: string
  createdAt: string
  withdrawnAt?: string
  passwordChangeRequired: boolean
  grade?: AdminMemberGrade
  addresses: AdminMemberAddress[]
}

/** PATCH /admin/members/{publicId} body. */
export interface AdminMemberUpdateRequest {
  name: string
  phone: string
}

/** PUT /admin/members/{publicId}/grade body. lockedUntil은 yyyy-MM-dd(오늘 이후). */
export interface AdminMemberGradeRequest {
  gradeCode: AdminBuyerGradeCode
  lockedUntil: string
}

/** 목록 화면 상태(URL query 단일 소스). status는 페이지가 고정 주입해 query에 싣지 않는다. */
export interface AdminMemberListQuery {
  keyword: string
  sort: AdminMemberSort
  page: number
  size: number
}

/** BE GET /admin/members 쿼리 파라미터(null·빈 값은 제외). */
export type AdminMemberApiParams = Record<string, string | number>
