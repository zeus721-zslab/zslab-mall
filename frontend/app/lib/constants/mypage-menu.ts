/**
 * 마이페이지 메뉴 단일 소스(FE-72). renew 사이드 메뉴·classic 허브·헤더 계정 메뉴가 모두 이 정의에서 파생한다.
 * description은 classic 허브 카드의 설명 문구다. FE-63: 취소·반품·교환은 주문 내역 탭으로 통합돼 별도 항목이 없다.
 */
export interface MypageMenuItem {
  to: string
  label: string
  description: string
}

export const MYPAGE_HOME_PATH = '/mypage'
export const MYPAGE_WITHDRAW_PATH = '/mypage/withdraw'

/** 메뉴 표시 순서(renew 사이드 메뉴 기준). */
export const MYPAGE_MENU_ITEMS: MypageMenuItem[] = [
  { to: MYPAGE_HOME_PATH, label: '홈', description: '주문 현황과 최근 주문' },
  { to: '/orders', label: '주문 내역', description: '주문·취소·반품·교환 진행 상태 확인' },
  { to: '/mypage/profile', label: '회원 정보', description: '이름·연락처 확인 및 수정' },
  { to: '/mypage/addresses', label: '배송지 관리', description: '주소록 추가·수정·삭제' },
  { to: '/mypage/password', label: '비밀번호 변경', description: '로그인 비밀번호 변경' },
  { to: MYPAGE_WITHDRAW_PATH, label: '회원 탈퇴', description: '계정 탈퇴' },
]

/** classic 허브(/mypage) 카드 목록. 허브 자신인 홈은 뺀다. */
export const MYPAGE_HUB_MENU_ITEMS: MypageMenuItem[] = MYPAGE_MENU_ITEMS.filter((item) => item.to !== MYPAGE_HOME_PATH)

/**
 * 헤더 계정 드롭다운 링크(FE-19). 회원 탈퇴는 파괴적 동작이라 헤더 상시 노출에서 제외하고(마이페이지 메뉴에서만 진입),
 * 홈은 헤더에서 "마이페이지"로 표시한다.
 */
export const ACCOUNT_MENU_ITEMS: { to: string; label: string }[] = MYPAGE_MENU_ITEMS
  .filter((item) => item.to !== MYPAGE_WITHDRAW_PATH)
  .map((item) => ({ to: item.to, label: item.to === MYPAGE_HOME_PATH ? '마이페이지' : item.label }))
