/**
 * 매뉴얼 캡처 개인정보 가림(C8 P1). 캡처 직전에 CSS로 blur를 주입한다 — 화면 코드는 건드리지 않고 지금 DOM에 있는 data-testid·구조만 쓴다.
 * 대상은 정찰 보고서(docs/track-manual/recon-report.md §6-5)의 개인정보 노출 18화면 + 상단바 계정 표시(이름 첫 글자·계정 메뉴).
 * 표 열 번호(td:nth-child)는 각 표의 headers 순서 기준이다(선택 체크박스 열을 쓰는 표 없음).
 */

/** 모든 캡처에 거는 선택자. */
export const PRIVACY_SELECTORS: string[] = [
  // 관리자 회원(AdminMemberListView · members/[id])
  '[data-testid="admin-member-table"] [data-testid="row-name"]',
  '[data-testid="admin-member-table"] [data-testid="row-email"]',
  '[data-testid="admin-member-table"] tbody td:nth-child(4)',
  '[data-testid="member-name"]',
  '[data-testid="member-email"]',
  '[data-testid="member-phone"]',
  '[data-testid="member-address-row"] > td:nth-child(2)',
  '[data-testid="member-address-row"] > td:nth-child(3)',
  '[data-testid="member-address-row"] > td:nth-child(4)',
  // 관리자 운영자·셀러
  '[data-testid="admin-operator-table"] [data-testid="row-name"]',
  '[data-testid="admin-operator-table"] [data-testid="row-email"]',
  '[data-testid="admin-seller-table"] tbody td:nth-child(1) .text-caption',
  '[data-testid="admin-seller-table"] tbody td:nth-child(3)',
  '[data-testid="seller-email"]',
  '[data-testid="seller-phone"]',
  '[data-testid="seller-ceo"]',
  '[data-testid="seller-member-name"]',
  '[data-testid="seller-member-row"] > td:nth-child(2)',
  '[data-testid="seller-bank-row-holder"]',
  // 관리자 주문·클레임·배송
  '[data-testid="admin-order-table"] tbody td:nth-child(3)',
  '[data-testid="order-buyer"] .v-card-text > .text-body-1',
  '[data-testid="order-buyer"] .v-card-text > .text-body-2',
  '[data-testid="order-shipping-address"] .v-card-text > div',
  '[data-testid="admin-claim-table"] tbody td:nth-child(3) .text-caption',
  '[data-testid="admin-delivery-table"] tbody td:nth-child(3)',
  '[data-testid="detail-shipping"] td',
  // 관리자 인박스·대시보드·통계·정산
  '[data-testid="inbox-claim-panel"] > .v-row > .v-col:last-child > .text-body-2',
  '[data-testid="inbox-delivery-panel"] > .v-row > .v-col-12 > .text-body-2',
  '[data-testid="inbox-settlement-panel"] > .v-row > .v-col-12 > .text-body-2',
  '[data-testid="dashboard-recent-orders-row"] p.text-caption',
  '[data-testid="top-buyers-name"]',
  '[data-testid="top-buyers-email"]',
  '[data-testid="settlement-contact-email"]',
  '[data-testid="settlement-contact-phone"]',
  '[data-testid="settlement-bank-account"]',
  // 셀러 주문·배송·정산계좌
  '[data-testid="seller-order-table"] tbody td:nth-child(5)',
  '[data-testid="order-detail-recipient"]',
  '[data-testid="order-detail-address"] .v-card-text > div',
  '[data-testid="seller-delivery-table"] tbody td:nth-child(3)',
  '[data-testid^="seller-bank-account-row-"] .v-list-item-subtitle',
  // 상단바 계정 표시(아바타 첫 글자는 항상 보이고, 이름·이메일은 계정 메뉴를 열 때만 보인다)
  '[data-testid="admin-display-name"]',
  '[data-testid="admin-account-menu"] .v-avatar',
  '[data-testid="seller-display-name"]',
  '[data-testid="seller-account-menu"] .v-avatar',
]

/**
 * 경로가 맞을 때만 거는 선택자. 페이지 헤더 설명은 testid가 없어 클래스로만 잡히는데, 전역으로 걸면 다른 화면의 일반 설명까지 흐려진다.
 * 회원 상세(이름·이메일)·셀러 상세(대표자명)만 헤더 설명에 개인정보가 들어간다.
 */
export const PATH_PRIVACY_SELECTORS: { pattern: RegExp; selectors: string[] }[] = [
  { pattern: /^\/admin\/members\/(?!sellers|admins|withdrawn)[^/]+$/, selectors: ['.adm-page-header__description:not([data-testid])'] },
  { pattern: /^\/admin\/members\/sellers\/[^/]+$/, selectors: ['.adm-page-header__description:not([data-testid])'] },
]

export function privacySelectorsFor(pathname: string): string[] {
  const extra = PATH_PRIVACY_SELECTORS.filter((rule) => rule.pattern.test(pathname)).flatMap((rule) => rule.selectors)
  return [...PRIVACY_SELECTORS, ...extra]
}
