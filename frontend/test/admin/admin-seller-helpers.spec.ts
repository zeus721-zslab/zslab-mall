import { describe, it, expect } from 'vitest'
import {
  DEFAULT_ADMIN_SELLER_QUERY,
  hasActiveFilters,
  parseAdminSellerQuery,
  toAdminSellerApiParams,
  toAdminSellerRouteQuery,
} from '#layers/admin/app/lib/admin-seller-query'
import {
  availableTransitions,
  canTransitionTo,
  formatTerminationBlocks,
  loginableMemberCount,
  memberDisplayName,
  sellerOnboardingChecklist,
  sellerStatusChipClass,
  sellerStatusLabel,
  terminateBlockedReason,
  toSellerErrorMessage,
  toSellerProductListPath,
  transitionConfirmMessage,
} from '#layers/admin/app/lib/admin-seller-view'
import { SELLER_TERMINATE_IRREVERSIBLE_NOTICE, type AdminSellerStatus } from '#layers/admin/app/lib/constants/admin-seller'
import { formatPercent, parsePercentInput, toPercentInput } from '#layers/admin/app/lib/admin-category-view'
import type { AdminSellerDetail, AdminSellerMember } from '#layers/admin/app/types/admin-seller'

// FE-40: 셀러 목록 URL 매핑·상태 배지·전이 판정·종료 차단 문구·구성원 계수·확인 문구·율 환산 순수 함수.
function detail(overrides: Partial<AdminSellerDetail> = {}): AdminSellerDetail {
  return {
    sellerPublicId: 'slr_A', companyName: '테스트샵', ceoName: '대표', status: 'ACTIVE', createdAt: '2026-09-18T10:00:00', updatedAt: '2026-09-18T10:00:00',
    members: [], productCount: 0, productCountByStatus: {}, orderCount: 0, confirmedSalesAmount: 0, settlements: [],
    terminable: true, terminationBlocks: [], warnings: { primaryBankAccountMissing: false, saleProductCount: 0 }, ...overrides,
  }
}

describe('admin-seller-query', () => {
  it('parse: 정상값·오값 정규화(미지의 status·음수 page·허용 외 size·51자 keyword)', () => {
    expect(parseAdminSellerQuery({ status: 'SUSPENDED', keyword: ' 리빙 ', page: '2', size: '50' })).toEqual({ status: 'SUSPENDED', keyword: '리빙', page: 2, size: 50 })
    expect(parseAdminSellerQuery({ status: 'FOO', page: '-1', size: '7', keyword: 'x'.repeat(60) })).toEqual({ status: null, keyword: 'x'.repeat(50), page: 0, size: 20 })
    expect(parseAdminSellerQuery({})).toEqual(DEFAULT_ADMIN_SELLER_QUERY)
  })
  it('route: 기본값 생략 · api: 빈 검색어·null status 제외 · hasActiveFilters', () => {
    expect(toAdminSellerRouteQuery({ status: null, keyword: '', page: 0, size: 20 })).toEqual({})
    expect(toAdminSellerRouteQuery({ status: 'PENDING', keyword: 'a', page: 1, size: 50 })).toEqual({ status: 'PENDING', keyword: 'a', page: '1', size: '50' })
    expect(toAdminSellerApiParams({ status: null, keyword: '  ', page: 0, size: 20 })).toEqual({ page: 0, size: 20 })
    expect(toAdminSellerApiParams({ status: 'ACTIVE', keyword: '101-81', page: 0, size: 20 })).toEqual({ status: 'ACTIVE', keyword: '101-81', page: 0, size: 20 })
    expect(hasActiveFilters(DEFAULT_ADMIN_SELLER_QUERY)).toBe(false)
    expect(hasActiveFilters({ ...DEFAULT_ADMIN_SELLER_QUERY, status: 'PENDING' })).toBe(true)
  })
})

describe('admin-seller-view', () => {
  it('상태 배지: ACTIVE 녹색·PENDING 노랑(warning)·SUSPENDED danger·TERMINATED 회색(neutral) + 라벨', () => {
    expect(sellerStatusChipClass('ACTIVE')).toBe('adm-chip adm-chip--success')
    expect(sellerStatusChipClass('PENDING')).toBe('adm-chip adm-chip--warning')
    expect(sellerStatusChipClass('SUSPENDED')).toBe('adm-chip adm-chip--danger')
    expect(sellerStatusChipClass('TERMINATED')).toBe('adm-chip adm-chip--neutral')
    expect(sellerStatusLabel('PENDING')).toBe('승인 대기')
  })

  it('전이 판정: state-machine §7 6전이만 허용·TERMINATED 불가역·같은 상태 불허', () => {
    expect(availableTransitions('PENDING')).toEqual(['ACTIVE', 'TERMINATED'])
    expect(availableTransitions('ACTIVE')).toEqual(['SUSPENDED', 'TERMINATED'])
    expect(availableTransitions('SUSPENDED')).toEqual(['ACTIVE', 'TERMINATED'])
    expect(availableTransitions('TERMINATED')).toEqual([])
    const all: AdminSellerStatus[] = ['PENDING', 'ACTIVE', 'SUSPENDED', 'TERMINATED']
    let allowed = 0
    for (const from of all) for (const to of all) if (canTransitionTo(from, to)) allowed++
    expect(allowed).toBe(6)
    expect(canTransitionTo('ACTIVE', 'ACTIVE')).toBe(false)
    expect(canTransitionTo('PENDING', 'SUSPENDED')).toBe(false)
  })

  it('terminationBlocks 문구: "미지급 정산 2건 / 진행 중 주문 6건 / 처리 중 클레임 1건"·비활성 사유·응답 SoT', () => {
    const blocks = [
      { code: 'UNPAID_SETTLEMENT' as const, count: 2 },
      { code: 'ORDER_ITEM_IN_PROGRESS' as const, count: 6 },
      { code: 'CLAIM_ACTIVE' as const, count: 1 },
    ]
    expect(formatTerminationBlocks(blocks)).toBe('미지급 정산 2건 / 진행 중 주문 6건 / 처리 중 클레임 1건')
    expect(formatTerminationBlocks([])).toBe('')
    expect(terminateBlockedReason(detail({ terminable: false, terminationBlocks: blocks }))).toBe('종료 불가: 미지급 정산 2건 / 진행 중 주문 6건 / 처리 중 클레임 1건')
    expect(terminateBlockedReason(detail({ terminable: true }))).toBeNull()
    // terminable=false인데 blocks가 비어 있어도(응답 불일치 방어) 비활성 사유는 있다
    expect(terminateBlockedReason(detail({ terminable: false, terminationBlocks: [] }))).toBe('지금은 종료할 수 없습니다.')
  })

  it('409 SELLER_ACTIVITY_IN_PROGRESS 오류 문구는 응답 blocks로 조립·그 외는 공용 표', () => {
    const conflict = { data: { code: 'SELLER_ACTIVITY_IN_PROGRESS', blocks: [{ code: 'UNPAID_SETTLEMENT', count: 1 }] } }
    expect(toSellerErrorMessage(conflict)).toBe('종료할 수 없습니다: 미지급 정산 1건')
    expect(toSellerErrorMessage({ data: { code: 'SELLER_ACTIVITY_IN_PROGRESS' } })).toContain('종료할 수 없습니다')
    expect(toSellerErrorMessage({ data: { code: 'SELLER_INVALID_STATE' } })).toBe('현재 셀러 상태에서 허용되지 않는 처리입니다.')
    expect(toSellerErrorMessage({ data: { code: 'SELLER_BUSINESS_NO_DUPLICATE' } })).toBe('이미 등록된 사업자번호입니다.')
  })

  it('구성원: 로그인 가능 수 = user 해소 ∧ 미탈퇴(셀러 2 사례: 유일 owner 탈퇴 → 0) · 표시명 폴백', () => {
    const withdrawnOwner: AdminSellerMember = { userPublicId: 'usr_1', email: 'o@t.local', roleCode: 'SELLER_OWNER', withdrawnAt: '2026-09-18T00:26:27' }
    const active: AdminSellerMember = { userPublicId: 'usr_2', name: '매니저', roleCode: 'SELLER_MANAGER' }
    const deleted: AdminSellerMember = { roleCode: 'SELLER_STAFF' }
    expect(loginableMemberCount([withdrawnOwner])).toBe(0)
    expect(loginableMemberCount([withdrawnOwner, active, deleted])).toBe(1)
    expect(loginableMemberCount([])).toBe(0)
    expect(memberDisplayName(active)).toBe('매니저')
    expect(memberDisplayName(withdrawnOwner)).toBe('o@t.local')
    expect(memberDisplayName(deleted)).toBe('삭제된 회원')
  })

  it('전이 확인 문구: 종료는 불가역 안내 필수 + 경고 병기·정지는 해제 가능·활성화는 PENDING/SUSPENDED 분기', () => {
    const terminate = transitionConfirmMessage(detail({ warnings: { primaryBankAccountMissing: true, saleProductCount: 9 } }), 'TERMINATED')
    expect(terminate).toContain(SELLER_TERMINATE_IRREVERSIBLE_NOTICE)
    expect(terminate).toContain('종료 후에는 어떤 상태로도 되돌릴 수 없습니다.')
    expect(terminate).toContain('판매중 상품 9건')
    expect(terminate).toContain('주 정산계좌가 없습니다')
    expect(transitionConfirmMessage(detail(), 'TERMINATED')).not.toContain('판매중 상품')
    expect(transitionConfirmMessage(detail(), 'SUSPENDED')).toContain('정지 해제(활성화)로 되돌릴 수 있습니다.')
    expect(transitionConfirmMessage(detail({ status: 'PENDING' }), 'ACTIVE')).toContain('입점을 승인합니다')
    expect(transitionConfirmMessage(detail({ status: 'SUSPENDED' }), 'ACTIVE')).toContain('정지를 해제합니다')
  })

  it('경로·율 환산: 상품 목록 셀러 필터 경로 · bp↔%(89-C 헬퍼 재사용)', () => {
    expect(toSellerProductListPath('slr_A')).toBe('/admin/products?sellerPublicId=slr_A')
    expect(formatPercent(1200)).toBe('12%')
    expect(toPercentInput(undefined)).toBe('')
    expect(parsePercentInput('5.25')).toEqual({ ok: true, basisPoints: 525 })
    expect(parsePercentInput('')).toEqual({ ok: true, basisPoints: null })
    expect(parsePercentInput('101').ok).toBe(false)
  })
})

// Track 96-1(FE-53·C-17): 온보딩 체크리스트 4항목 판정은 상세 응답 기존 필드만 사용·미충족 항목의 이동 대상.
describe('sellerOnboardingChecklist(C-17)', () => {
  const member: AdminSellerMember = { userPublicId: 'usr_1', email: 'a@b.c', name: '홍길동', roleCode: 'SELLER_OWNER', joinedAt: '2026-09-18T10:00:00' }

  it('전부 충족: ACTIVE·주 계좌 있음·로그인 가능 구성원 1·판매중 1 → done 4', () => {
    const items = sellerOnboardingChecklist(detail({ members: [member], warnings: { primaryBankAccountMissing: false, saleProductCount: 1 } }))
    expect(items.map((item) => item.key)).toEqual(['active', 'bankAccount', 'member', 'product'])
    expect(items.every((item) => item.done)).toBe(true)
  })

  it('미충족 판정·이동 대상: PENDING → info 카드 · 계좌 없음 → 계좌 카드 · 탈퇴 구성원만 → 구성원 카드 · 판매중 0 → 상품 목록(셀러 필터) 경로', () => {
    const items = sellerOnboardingChecklist(detail({
      status: 'PENDING', members: [{ ...member, withdrawnAt: '2026-09-19T10:00:00' }],
      warnings: { primaryBankAccountMissing: true, saleProductCount: 0 },
    }))
    expect(items.map((item) => item.done)).toEqual([false, false, false, false])
    expect(items[0]?.target).toEqual({ kind: 'card', testId: 'seller-info' })
    expect(items[1]?.target).toEqual({ kind: 'card', testId: 'seller-bank-account' })
    expect(items[2]?.target).toEqual({ kind: 'card', testId: 'seller-members' })
    expect(items[3]?.target).toEqual({ kind: 'route', to: toSellerProductListPath('slr_A') })
    expect(items.filter((item) => !item.done).every((item) => item.hint.length > 0)).toBe(true)
  })
})
