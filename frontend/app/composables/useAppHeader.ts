import { BUYER_ROLE } from '~/lib/constants/auth'

// 계정 드롭다운 링크 항목(FE-19). 회원 탈퇴는 파괴적 동작이라 헤더 상시 노출 대상에서 제외(/mypage 허브에서만 진입).
// FE-63: 취소·반품·교환 내역은 주문내역 탭으로 통합돼 별도 항목을 두지 않는다(진입점 일원화).
const ACCOUNT_MENU_ITEMS: { to: string; label: string }[] = [
  { to: '/mypage', label: '마이페이지' },
  { to: '/orders', label: '주문내역' },
  { to: '/mypage/profile', label: '회원정보 수정' },
  { to: '/mypage/password', label: '비밀번호 변경' },
  { to: '/mypage/addresses', label: '배송지 관리' },
]

/**
 * 구매자 헤더 상태·동작(FE-09 STEP 3·FE-19·FE-20). classic AppHeader와 renew LayoutShell(레이아웃이 vm으로 전달·FE-69)이
 * 같은 로직을 쓰도록 AppHeader에서 추출했다. 검색·카테고리 메뉴·계정 메뉴·로그아웃을 제공한다.
 */
export function useAppHeader() {
  const auth = useAuthStore()
  const cart = useCartStore()
  const route = useRoute()

  // 검색창(FE-20): submit 시 trim 값으로 /search?keyword= 이동, 빈 값이면 이동하지 않는다.
  // /search 진입·keyword 변경 시 입력창에 URL 값을 반영한다(뒤로가기·직접 진입 정합).
  const searchKeyword = ref<string>(typeof route.query.keyword === 'string' ? route.query.keyword : '')
  watch(
    () => route.query.keyword,
    (value) => {
      searchKeyword.value = typeof value === 'string' ? value : ''
    },
  )

  async function handleSearchSubmit(): Promise<void> {
    const keyword = searchKeyword.value.trim()
    if (keyword === '') {
      return
    }
    await navigateTo({ path: '/search', query: { keyword } })
  }

  // 카테고리 드롭다운(FE-20): 전체 상품 + 루트 카테고리. 조회 실패·빈 목록이면 "전체 상품"만 남긴다.
  const { data: categories, error: categoriesError } = useCategories()
  const categoryMenuItems = computed(() => (categoriesError.value ? [] : categories.value ?? []))

  // route.meta.middleware는 단일 문자열 'buyer' 또는 배열로 노출될 수 있어 양쪽 모두 방어적으로 판정한다.
  function isBuyerProtectedRoute(): boolean {
    const middleware = route.meta.middleware
    if (Array.isArray(middleware)) return middleware.includes('buyer')
    return middleware === 'buyer'
  }

  // 로그아웃은 UI 계층에서 auth·cart를 순차 조합한다(store 간 결합은 store 밖에서).
  // 미들웨어는 네비게이션 시에만 평가되므로, 머문 페이지가 BUYER 보호 페이지면 홈으로 이탈시켜 재가드한다(공개 페이지는 잔류).
  async function handleLogout(): Promise<void> {
    auth.logout()
    cart.clear()
    if (isBuyerProtectedRoute()) {
      await navigateTo('/')
    }
  }

  // FE-22 D-2: 단일 쿠키 세션이라 ADMIN 토큰도 isAuthenticated=true → BUYER 전용 메뉴는 role=BUYER일 때만 노출한다.
  const isBuyerSignedIn = computed(() => auth.isAuthenticated && auth.role === BUYER_ROLE)
  const cartCount = computed(() => cart.count)

  return {
    auth,
    cart,
    searchKeyword,
    handleSearchSubmit,
    categoryMenuItems,
    accountMenuItems: ACCOUNT_MENU_ITEMS,
    handleLogout,
    isBuyerSignedIn,
    cartCount,
  }
}
