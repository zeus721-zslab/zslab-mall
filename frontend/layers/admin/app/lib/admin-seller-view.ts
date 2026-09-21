import type {
  AdminSellerDetail,
  AdminSellerMember,
  AdminSellerTerminationBlock,
} from '#layers/admin/app/types/admin-seller'
import {
  ADMIN_SELLER_STATUS_LABEL,
  ADMIN_SELLER_STATUS_TONE,
  ADMIN_SELLER_TERMINATION_BLOCK_LABEL,
  ADMIN_SELLER_TRANSITIONS,
  SELLER_TERMINATE_IRREVERSIBLE_NOTICE,
  type AdminSellerStatus,
} from '#layers/admin/app/lib/constants/admin-seller'
import { extractErrorCode, toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'

/** 셀러 관리 화면 순수 판정·문구(FE-40). 컴포넌트가 아니라 여기 두어 vitest로 고정한다. */

/** 상태 배지 CSS 클래스(admin-vuetify.css .adm-chip--*). */
export function sellerStatusChipClass(status: AdminSellerStatus): string {
  return `adm-chip adm-chip--${ADMIN_SELLER_STATUS_TONE[status]}`
}

export function sellerStatusLabel(status: AdminSellerStatus): string {
  return ADMIN_SELLER_STATUS_LABEL[status]
}

/** 현재 상태에서 가능한 목표 상태(전이 매트릭스·TERMINATED는 빈 배열). */
export function availableTransitions(status: AdminSellerStatus): Exclude<AdminSellerStatus, 'PENDING'>[] {
  return ADMIN_SELLER_TRANSITIONS[status] as Exclude<AdminSellerStatus, 'PENDING'>[]
}

export function canTransitionTo(from: AdminSellerStatus, to: AdminSellerStatus): boolean {
  return (ADMIN_SELLER_TRANSITIONS[from] as AdminSellerStatus[]).includes(to)
}

/** 종료 차단 문구: "미지급 정산 2건 / 진행 중 주문 6건 / 처리 중 클레임 1건". 비어 있으면 빈 문자열. */
export function formatTerminationBlocks(blocks: AdminSellerTerminationBlock[]): string {
  return blocks.map((block) => `${ADMIN_SELLER_TERMINATION_BLOCK_LABEL[block.code]} ${block.count}건`).join(' / ')
}

/**
 * 종료 버튼 비활성 사유(툴팁). 종료 가능이면 null. 응답의 terminable·terminationBlocks가 SoT이며(BE와 같은 가드 판정) FE가 재계산하지 않는다.
 */
export function terminateBlockedReason(detail: Pick<AdminSellerDetail, 'terminable' | 'terminationBlocks'>): string | null {
  if (detail.terminable) return null
  const blocks = formatTerminationBlocks(detail.terminationBlocks)
  return blocks === '' ? '지금은 종료할 수 없습니다.' : `종료 불가: ${blocks}`
}

/** 로그인 가능한 구성원 수 = user가 해소됐고(soft-delete 아님) 탈퇴하지 않은 구성원. 0이면 상세에 경고를 띄운다. */
export function loginableMemberCount(members: AdminSellerMember[]): number {
  return members.filter((member) => member.userPublicId !== undefined && member.withdrawnAt === undefined).length
}

/** 온보딩 체크 항목(Track 96-1 FE-53·C-17). 미충족이면 card(같은 화면 카드로 스크롤) 또는 route(다른 화면)로 이동한다. */
export interface SellerOnboardingItem {
  key: 'active' | 'bankAccount' | 'member' | 'product'
  label: string
  done: boolean
  /** 미충족일 때 안내 1줄. */
  hint: string
  target: { kind: 'card'; testId: string } | { kind: 'route'; to: string }
}

/**
 * 입점 온보딩 4항목(ACTIVE·주 계좌·로그인 가능 구성원·판매중 상품 ≥1). 판정은 상세 응답 기존 필드만 쓴다(status·warnings·members·
 * warnings.saleProductCount). 순서 = 실제 입점 절차 순서.
 */
export function sellerOnboardingChecklist(detail: AdminSellerDetail): SellerOnboardingItem[] {
  return [
    { key: 'active', label: '셀러 상태 ACTIVE', done: detail.status === 'ACTIVE', hint: '기본 정보 카드에서 상태를 전이하세요.',
      target: { kind: 'card', testId: 'seller-info' } },
    { key: 'bankAccount', label: '주 정산계좌 등록', done: !detail.warnings.primaryBankAccountMissing, hint: '정산계좌 카드에서 계좌를 등록하세요.',
      target: { kind: 'card', testId: 'seller-bank-account' } },
    { key: 'member', label: '로그인 가능한 구성원', done: loginableMemberCount(detail.members) > 0, hint: '구성원 카드에서 기존 회원을 추가하세요.',
      target: { kind: 'card', testId: 'seller-members' } },
    { key: 'product', label: '판매중 상품 1개 이상', done: detail.warnings.saleProductCount > 0, hint: '상품 목록에서 셀러 상품을 승인(판매중)하세요.',
      target: { kind: 'route', to: toSellerProductListPath(detail.sellerPublicId) } },
  ]
}

/** 구성원 표시명: 이름 → 이메일 → (삭제된 회원). */
export function memberDisplayName(member: AdminSellerMember): string {
  return member.name ?? member.email ?? (member.userPublicId ? member.userPublicId : '삭제된 회원')
}

/**
 * 전이 확인 문구(상태별). 종료는 불가역 안내를 반드시 포함하고 경고(주 계좌 없음·판매중 상품)를 병기한다.
 */
export function transitionConfirmMessage(
  detail: Pick<AdminSellerDetail, 'companyName' | 'status' | 'warnings'>,
  target: Exclude<AdminSellerStatus, 'PENDING'>,
): string {
  const name = detail.companyName
  if (target === 'TERMINATED') {
    const lines = [`${name} 셀러를 종료합니다.`, SELLER_TERMINATE_IRREVERSIBLE_NOTICE,
      '종료 즉시 이 셀러의 상품은 카탈로그에서 사라지고 담기·주문·재결제가 차단됩니다. 사유는 종료 아카이브와 감사 이력에 기록됩니다.']
    if (detail.warnings.saleProductCount > 0) lines.push(`판매중 상품 ${detail.warnings.saleProductCount}건이 있습니다(상품 상태는 바뀌지 않습니다).`)
    if (detail.warnings.primaryBankAccountMissing) lines.push('주 정산계좌가 없습니다. 남은 매출의 정산 지급이 불가능할 수 있습니다.')
    return lines.join('\n')
  }
  if (target === 'SUSPENDED') {
    return [`${name} 셀러를 정지합니다.`,
      '정지 즉시 이 셀러의 상품은 카탈로그에서 사라지고 담기·주문·재결제가 차단됩니다. 진행 중인 주문·정산은 계속 처리됩니다.',
      '정지 해제(활성화)로 되돌릴 수 있습니다.'].join('\n')
  }
  // ACTIVE
  if (detail.status === 'PENDING') {
    return [`${name} 셀러의 입점을 승인합니다.`, '승인 즉시 판매중 상품이 카탈로그에 노출되고 구매가 가능해집니다.'].join('\n')
  }
  return [`${name} 셀러의 정지를 해제합니다.`, '해제 즉시 판매중 상품이 카탈로그에 다시 노출되고 구매가 가능해집니다.'].join('\n')
}

/** 상품 수 클릭 → 상품 목록 셀러 필터(admin-product-query.ts sellerPublicId). */
export function toSellerProductListPath(sellerPublicId: string): string {
  return `/admin/products?sellerPublicId=${encodeURIComponent(sellerPublicId)}`
}

/** 셀러별 정산 이력 화면 경로(pages/admin/settlements/sellers.vue ?seller=). */
export function toSellerSettlementsPath(sellerPublicId: string): string {
  return `/admin/settlements/sellers?seller=${encodeURIComponent(sellerPublicId)}`
}

/**
 * 오류 문구: 409 SELLER_ACTIVITY_IN_PROGRESS는 응답 blocks로 어느 가드에 몇 건인지 조립한다(코드 문구만으로는 원인을 알 수 없음).
 * 그 외는 공용 코드 표 → detail → 일반 문구.
 */
export function toSellerErrorMessage(error: unknown): string {
  if (extractErrorCode(error) === 'SELLER_ACTIVITY_IN_PROGRESS') {
    const blocks = (error as { data?: { blocks?: unknown } } | null)?.data?.blocks
    if (Array.isArray(blocks) && blocks.length > 0) {
      return `종료할 수 없습니다: ${formatTerminationBlocks(blocks as AdminSellerTerminationBlock[])}`
    }
  }
  return toAdminErrorMessage(error)
}
