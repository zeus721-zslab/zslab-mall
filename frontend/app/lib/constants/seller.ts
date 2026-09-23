/**
 * 셀러 상태·구성원 역할 라벨 공용 단일 소스(Track 102 FE-64).
 *
 * 같은 값을 관리자 레이어(constants/admin-seller.ts)와 셀러 레이어(constants/seller-me.ts)가 각자 라벨링해
 * ACTIVE가 "활성"과 "정상"으로, SELLER_MANAGER가 "매니저"와 "관리자"로 갈려 있었다. 특히 셀러 화면의 "관리자"는
 * 같은 화면이 말하는 플랫폼 관리자와 동음이의였다. 라벨 문자열만 여기로 모으고 각 레이어는 기존 이름으로 re-export한다.
 */

/** BE SellerStatus 4값(seller.status ENUM). */
export type SellerStatusCode = 'PENDING' | 'ACTIVE' | 'SUSPENDED' | 'TERMINATED'

/** 상태 전이 3종(활성·정지·종료)과 같은 말을 쓴다 — "정상"은 전이 이름에 없어 어느 조작의 결과인지 읽히지 않는다. */
export const SELLER_STATUS_LABELS: Record<SellerStatusCode, string> = {
  PENDING: '승인 대기',
  ACTIVE: '활성',
  SUSPENDED: '정지',
  TERMINATED: '종료',
}

/** BE RoleCode 중 셀러 구성원 역할 3값(seller_user.role_id → role.code·V11 시드). */
export type SellerMemberRoleCode = 'SELLER_OWNER' | 'SELLER_MANAGER' | 'SELLER_STAFF'

/** SELLER_MANAGER는 매니저 — 플랫폼 관리자와 같은 말을 쓰면 셀러 화면에서 누구를 가리키는지 구분되지 않는다. */
export const SELLER_MEMBER_ROLE_LABELS: Record<SellerMemberRoleCode, string> = {
  SELLER_OWNER: '대표',
  SELLER_MANAGER: '매니저',
  SELLER_STAFF: '담당자',
}
