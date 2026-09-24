<script setup lang="ts">
// FE-03 기본 레이아웃 셸. 헤더/푸터 고정·본문 flex-1로 푸터를 화면 하단에 고정한다.
</script>

<template>
  <div class="flex min-h-screen flex-col bg-surface-page">
    <AppHeader />
    <main class="flex-1">
      <slot />
    </main>
    <AppFooter />
  </div>
</template>
