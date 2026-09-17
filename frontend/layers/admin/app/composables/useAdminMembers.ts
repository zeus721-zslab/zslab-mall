import type {
  AdminMemberDetail,
  AdminMemberGradeRequest,
  AdminMemberListQuery,
  AdminMemberListResponse,
  AdminMemberUpdateRequest,
} from '#layers/admin/app/types/admin-member'
import type { AdminOrderListResponse } from '#layers/admin/app/types/admin-order'
import type { AdminClaimListResponse } from '#layers/admin/app/types/admin-claim'
import type { ClaimType } from '~/lib/constants/claim'
import type { AdminMemberStatus } from '#layers/admin/app/lib/constants/admin-member'
import { toAdminMemberApiParams } from '#layers/admin/app/lib/admin-member-query'
import { useAdminApi } from '#layers/admin/app/composables/useAdminApi'

/**
 * 관리자 회원 관리 API 호출 모음(Track 84 FE·D-178 BE·useAdminOrders 패턴). 전부 useAdminApi(admin_token Bearer·401 처리) 경유이며
 * 상태(로딩·에러)는 호출부(페이지·다이얼로그)가 소유한다. 회원 상세 주문/클레임 탭은 기존 주문·클레임 목록 API에 buyerPublicId만
 * 붙여 읽는다(회원 전용 조회 API 없음).
 */
export function useAdminMembers() {
  const api = useAdminApi()

  // 템플릿 리터럴 경로는 nitro 타입드 라우트 추론이 과도해(TS2321) string으로 고정한다(useAdminOrders 선례).
  function memberPath(publicId: string, suffix = ''): string {
    return `/v1/admin/members/${publicId}${suffix}`
  }

  function list(query: AdminMemberListQuery, status: AdminMemberStatus): Promise<AdminMemberListResponse> {
    return api<AdminMemberListResponse>('/v1/admin/members', { query: toAdminMemberApiParams(query, status) })
  }

  function get(publicId: string): Promise<AdminMemberDetail> {
    return api<AdminMemberDetail>(memberPath(publicId))
  }

  function update(publicId: string, body: AdminMemberUpdateRequest): Promise<void> {
    return api<void>(memberPath(publicId), { method: 'PATCH', body })
  }

  function withdraw(publicId: string): Promise<void> {
    return api<void>(memberPath(publicId, '/withdraw'), { method: 'POST' })
  }

  function resetPassword(publicId: string): Promise<void> {
    return api<void>(memberPath(publicId, '/password-reset'), { method: 'POST' })
  }

  function changeGrade(publicId: string, body: AdminMemberGradeRequest): Promise<void> {
    return api<void>(memberPath(publicId, '/grade'), { method: 'PUT', body })
  }

  /** 회원 주문 탭: GET /admin/orders?buyerPublicId=… (최신순·페이지). */
  function listOrders(publicId: string, page: number, size: number): Promise<AdminOrderListResponse> {
    return api<AdminOrderListResponse>('/v1/admin/orders', { query: { buyerPublicId: publicId, sort: 'LATEST', page, size } })
  }

  /** 회원 취소·반품·교환 탭: GET /admin/claims?buyerPublicId=…&type=… (최신순·페이지). */
  function listClaims(publicId: string, type: ClaimType, page: number, size: number): Promise<AdminClaimListResponse> {
    return api<AdminClaimListResponse>('/v1/admin/claims', { query: { buyerPublicId: publicId, type, sort: 'LATEST', page, size } })
  }

  return { list, get, update, withdraw, resetPassword, changeGrade, listOrders, listClaims }
}
