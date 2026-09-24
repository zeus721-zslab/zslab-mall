<script setup lang="ts">
import type { LayoutShellVm } from '~/skins/contracts/layout'

// 구매자 기본 레이아웃. 마크업은 현재 스킨의 LayoutShell 뷰가 담당한다(FE-67). data-skin은 스킨별 CSS 범위 지정용.
const skinName = useSkinName()
useHead({ htmlAttrs: { 'data-skin': skinName } })

// 헤더 상태는 스킨이 layoutHeader를 선언했을 때만 만든다(FE-69). classic 셸은 AppHeader가 같은 로직(useAppHeader)을 직접 쓴다.
function createShellVm(): LayoutShellVm {
  const header = useAppHeader()
  return reactive({
    searchKeyword: header.searchKeyword,
    handleSearchSubmit: header.handleSearchSubmit,
    categoryMenuItems: header.categoryMenuItems,
    accountMenuItems: header.accountMenuItems,
    handleLogout: header.handleLogout,
    isBuyerSignedIn: header.isBuyerSignedIn,
    cartCount: header.cartCount,
  })
}
const shellVm = useSkinNeeds('layoutHeader') ? createShellVm() : undefined
</script>

<template>
  <component :is="useSkinView('LayoutShell')" :vm="shellVm">
    <slot />
  </component>
</template>
