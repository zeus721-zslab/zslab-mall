import type {
  AdminSellerBankAccountPrimaryRequest,
  AdminSellerBankAccountRegisterRequest,
  AdminSellerBankAccountRow,
  AdminSellerBankAccountUpdateRequest,
  AdminSellerDetail,
  AdminSellerListQuery,
  AdminSellerListResponse,
  AdminSellerMember,
  AdminSellerMemberAddRequest,
  AdminSellerMemberRemoveRequest,
  AdminSellerMemberRoleChangeRequest,
  AdminSellerProvisionRequest,
  AdminSellerStatusChangeRequest,
  AdminSellerUpdateRequest,
} from '#layers/admin/app/types/admin-seller'
import { toAdminSellerApiParams } from '#layers/admin/app/lib/admin-seller-query'
import { useAdminApi } from '#layers/admin/app/composables/useAdminApi'

/**
 * 관리자 셀러 관리 API 호출 모음(FE-40·D-187 BE·useAdminMembers 패턴). 전부 useAdminApi(admin_token Bearer·401 처리) 경유이며
 * 상태(로딩·에러)는 호출부가 소유한다. 기존 드롭다운용 전량 목록(GET /admin/sellers)은 useAdminProducts·useAdminSettlements가 계속 쓴다.
 */
export function useAdminSellers() {
  const api = useAdminApi()

  // 템플릿 리터럴 경로는 nitro 타입드 라우트 추론이 과도해(TS2321) string으로 고정한다(useAdminMembers 선례).
  function sellerPath(sellerPublicId: string, suffix = ''): string {
    return `/v1/admin/sellers/${sellerPublicId}${suffix}`
  }

  function list(query: AdminSellerListQuery): Promise<AdminSellerListResponse> {
    return api<AdminSellerListResponse>('/v1/admin/sellers/page', { query: toAdminSellerApiParams(query) })
  }

  /** 승인 대기 건수(목록 상단 안내용·size 1로 totalCount만 읽는다). */
  function countPending(): Promise<number> {
    return api<AdminSellerListResponse>('/v1/admin/sellers/page', { query: { status: 'PENDING', page: 0, size: 1 } })
      .then((response) => response.totalCount)
  }

  function get(sellerPublicId: string): Promise<AdminSellerDetail> {
    return api<AdminSellerDetail>(sellerPath(sellerPublicId))
  }

  /** 전이 응답은 전이 후 상세(terminable 등 갱신 반영)라 화면이 재조회 없이 바로 쓴다. */
  function changeStatus(sellerPublicId: string, body: AdminSellerStatusChangeRequest): Promise<AdminSellerDetail> {
    return api<AdminSellerDetail>(sellerPath(sellerPublicId, '/status'), { method: 'PATCH', body })
  }

  /** 204 → 호출부가 상세를 다시 읽는다. */
  function update(sellerPublicId: string, body: AdminSellerUpdateRequest): Promise<void> {
    return api<void>(sellerPath(sellerPublicId), { method: 'PUT', body })
  }

  function provision(body: AdminSellerProvisionRequest): Promise<{ sellerPublicId: string }> {
    return api<{ sellerPublicId: string }>('/v1/admin/sellers', { method: 'POST', body })
  }

  // ---------- 정산계좌(FE-41·D-188) ----------

  /** 등록 201 → 계좌 행(끝 4자리). 호출부는 상세를 다시 읽는다(주 계좌·경고 갱신). */
  function registerBankAccount(sellerPublicId: string, body: AdminSellerBankAccountRegisterRequest): Promise<AdminSellerBankAccountRow> {
    return api<AdminSellerBankAccountRow>(sellerPath(sellerPublicId, '/bank-accounts'), { method: 'POST', body })
  }

  /** 수정 204(정산 참조 행 409 SELLER_BANK_ACCOUNT_REFERENCED) → 호출부가 상세를 다시 읽는다. */
  function updateBankAccount(sellerPublicId: string, bankAccountId: number, body: AdminSellerBankAccountUpdateRequest): Promise<void> {
    return api<void>(sellerPath(sellerPublicId, `/bank-accounts/${bankAccountId}`), { method: 'PUT', body })
  }

  /** 주 계좌 전환 204(이미 주 계좌 422 SELLER_BANK_ACCOUNT_INVALID_STATE) → 호출부가 상세를 다시 읽는다. */
  function changePrimaryBankAccount(sellerPublicId: string, bankAccountId: number, body: AdminSellerBankAccountPrimaryRequest): Promise<void> {
    return api<void>(sellerPath(sellerPublicId, `/bank-accounts/${bankAccountId}/primary`), { method: 'PATCH', body })
  }

  // ---------- 구성원(FE-42·D-189) ----------

  /** 추가 201 → 구성원 행(joinedAt 포함). 호출부는 상세를 다시 읽는다(로그인 가능 구성원 경고·가드 판정 갱신). */
  function addMember(sellerPublicId: string, body: AdminSellerMemberAddRequest): Promise<AdminSellerMember> {
    return api<AdminSellerMember>(sellerPath(sellerPublicId, '/members'), { method: 'POST', body })
  }

  /** 제거 204(사유 본문·마지막 활성 OWNER 409 SELLER_LAST_OWNER) → 호출부가 상세를 다시 읽는다. */
  function removeMember(sellerPublicId: string, userPublicId: string, body: AdminSellerMemberRemoveRequest): Promise<void> {
    return api<void>(sellerPath(sellerPublicId, `/members/${userPublicId}`), { method: 'DELETE', body })
  }

  /** 역할 변경 204(같은 역할 422·마지막 활성 OWNER 강등 409) → 호출부가 상세를 다시 읽는다. */
  function changeMemberRole(sellerPublicId: string, userPublicId: string, body: AdminSellerMemberRoleChangeRequest): Promise<void> {
    return api<void>(sellerPath(sellerPublicId, `/members/${userPublicId}/role`), { method: 'PATCH', body })
  }

  return {
    list, countPending, get, changeStatus, update, provision, registerBankAccount, updateBankAccount, changePrimaryBankAccount,
    addMember, removeMember, changeMemberRole,
  }
}
