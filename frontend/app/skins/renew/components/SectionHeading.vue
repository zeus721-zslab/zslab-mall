<script setup lang="ts">
// renew 섹션 제목: 영문 태그(mono) + 제목(나눔고딕 700) + 선택적 "전체 보기" 링크 + 선택적 오른쪽 동작(slot).
defineProps<{
  tag: string
  title: string
  moreTo?: string
}>()
</script>

<template>
  <div class="mb-6 flex items-end justify-between gap-4">
    <div>
      <p class="font-mono text-xs font-medium uppercase tracking-widest text-sub">{{ tag }}</p>
      <h2 class="mt-1 text-2xl font-bold tracking-tight text-ink">{{ title }}</h2>
    </div>
    <NuxtLink
      v-if="moreTo"
      :to="moreTo"
      class="flex min-h-11 shrink-0 items-center rounded-full px-4 text-sm font-bold text-sub transition duration-200 hover:bg-surface-muted hover:text-ink"
    >
      전체 보기
    </NuxtLink>
    <!-- 제목 줄 오른쪽 동작(예: 좌우 이동 버튼). -->
    <slot />
  </div>
</template>
