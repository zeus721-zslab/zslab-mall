<script setup lang="ts">
// renew 섹션 제목: 영문 눈썹(caption) + 제목 + 선택적 "전체 보기" 링크 + 선택적 오른쪽 동작(slot).
// size: 메인 큐레이션 섹션은 h1 스케일, 보조 섹션은 h2 스케일(FE-76). 태그는 둘 다 h2(페이지 h1은 히어로·상품명).
withDefaults(
  defineProps<{
    tag: string
    title: string
    moreTo?: string
    size?: 'h1' | 'h2'
  }>(),
  { moreTo: undefined, size: 'h2' },
)
</script>

<template>
  <div class="mb-6 flex items-end justify-between gap-4">
    <div>
      <p class="text-caption uppercase tracking-[0.12em] text-sub">{{ tag }}</p>
      <h2 :class="['mt-1 text-ink', size === 'h1' ? 'text-h1' : 'text-h2']">{{ title }}</h2>
    </div>
    <NuxtLink v-if="moreTo" :to="moreTo" class="btn btn-tertiary btn-sm shrink-0 max-md:min-h-11">
      전체 보기
    </NuxtLink>
    <!-- 제목 줄 오른쪽 동작(예: 좌우 이동 버튼). -->
    <slot />
  </div>
</template>
