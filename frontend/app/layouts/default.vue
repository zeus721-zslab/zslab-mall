<script setup lang="ts">
import type { LayoutShellVm } from '~/skins/contracts/layout'
import FaqAssistant from '~/components/faq/FaqAssistant.vue'

// 구매자 기본 레이아웃. 마크업은 현재 스킨의 LayoutShell 뷰가 담당한다(FE-67). data-skin은 스킨별 CSS 범위 지정용.
const skinName = useSkinName()
useHead({ htmlAttrs: { 'data-skin': skinName } })

// 화면 자체 탭 줄이 있는 경로(FE-82 목록 카테고리 탭 · Track 105-4g-3 마이페이지 틀 메뉴 — MypageFrame을 쓰는 화면 전부).
// 셸은 이 경로의 <768에서 헤더 카테고리 줄을 숨겨 가로 줄이 겹겹이 쌓이지 않게 한다.
const PAGE_TAB_ROW_PATHS = ['/products', '/mypage', '/orders']
const PAGE_TAB_ROW_PREFIXES = ['/categories/', '/mypage/', '/orders/', '/claims/']

// 헤더 상태는 스킨이 layoutHeader를 선언했을 때만 만든다(FE-69). classic 셸은 AppHeader가 같은 로직(useAppHeader)을 직접 쓴다.
function createShellVm(): LayoutShellVm {
  const header = useAppHeader()
  const route = useRoute()
  return reactive({
    searchKeyword: header.searchKeyword,
    handleSearchSubmit: header.handleSearchSubmit,
    categoryMenuItems: header.categoryMenuItems,
    accountMenuItems: header.accountMenuItems,
    handleLogout: header.handleLogout,
    isBuyerSignedIn: header.isBuyerSignedIn,
    cartCount: header.cartCount,
    hasPageTabRow: computed(
      () => PAGE_TAB_ROW_PATHS.includes(route.path) || PAGE_TAB_ROW_PREFIXES.some((prefix) => route.path.startsWith(prefix)),
    ),
  })
}
const shellVm = useSkinNeeds('layoutHeader') ? createShellVm() : undefined
</script>

<template>
  <!-- display: contents — 채팅 도우미를 셸 밖 형제로 붙이기 위한 단일 루트(레이아웃 박스는 만들지 않는다). -->
  <div class="contents">
    <component :is="useSkinView('LayoutShell')" :vm="shellVm">
      <slot />
    </component>
    <!-- 채팅 도우미(Track 106-3): 스킨과 무관하게 구매자 화면에만 붙인다. 대화 복원이 브라우저 저장소를 쓰므로 SSR에서는 그리지 않는다. -->
    <ClientOnly>
      <FaqAssistant />
    </ClientOnly>
  </div>
</template>
