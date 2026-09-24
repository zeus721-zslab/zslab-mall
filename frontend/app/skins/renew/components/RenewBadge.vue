<script setup lang="ts">
// renew 공용 배지(FE-76). 상태·유형 같은 짧은 라벨을 의미별 파스텔 한 쌍(bg/ink)으로 표시한다 — 높이 22px · 12px/600 · 알약형.
// 라벨은 본문 흐름대로 읽히면 되므로 role을 두지 않는다(알림은 RenewNotice).
export type RenewBadgeTone = 'info' | 'success' | 'warning' | 'danger' | 'neutral'

const props = defineProps<{ tone: RenewBadgeTone }>()

const TONE_CLASSES: Record<RenewBadgeTone, string> = {
  info: 'bg-(--pastel-lavender-bg) text-(--pastel-lavender-ink)',
  success: 'bg-(--pastel-mint-bg) text-(--pastel-mint-ink)',
  warning: 'bg-(--pastel-butter-bg) text-(--pastel-butter-ink)',
  danger: 'bg-(--pastel-pink-bg) text-(--pastel-pink-ink)',
  neutral: 'bg-(--pastel-periwinkle-bg) text-(--pastel-periwinkle-ink)',
}
</script>

<template>
  <span
    :data-tone="props.tone"
    :class="['inline-flex h-[22px] shrink-0 items-center whitespace-nowrap rounded-full px-2.5 text-xs font-semibold leading-none', TONE_CLASSES[props.tone]]"
  >
    <slot />
  </span>
</template>
