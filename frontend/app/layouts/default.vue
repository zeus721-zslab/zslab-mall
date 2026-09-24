<script setup lang="ts">
// 구매자 기본 레이아웃. 마크업은 현재 스킨의 LayoutShell 뷰가 담당한다(FE-67). data-skin은 스킨별 CSS 범위 지정용.
const skinName = useSkinName()
useHead({ htmlAttrs: { 'data-skin': skinName } })
</script>

<template>
  <component :is="useSkinView('LayoutShell')">
    <slot />
  </component>
</template>
