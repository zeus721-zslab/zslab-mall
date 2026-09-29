<script setup lang="ts">
// renew 분포 막대(Track 106-1). 한 줄 = 이름 · 막대 · 값 문구. 막대는 보조 시각화라 읽지 않고(aria-hidden) 이름과 값 문구가 뜻을 전한다.
// 폭이 바뀔 때만 늘어나는 전환(motion-safe) — 움직임 줄이기 설정이면 바로 그린다. strong = 가장 많은 항목 강조(포인트색).
// stacked = 이름이 긴 막대(키워드 집계 "묶음 · 키워드")용 — 이름을 막대 위 한 줄에 전부 보이고 막대·값은 그 아래에 둔다(좁은 이름 칸에서 잘리지 않게).
const props = withDefaults(defineProps<{ label: string; ratio: number; valueText: string; strong?: boolean; stacked?: boolean }>(), {
  strong: false,
  stacked: false,
})

const width = computed(() => `${Math.round(Math.min(Math.max(props.ratio, 0), 1) * 100)}%`)
</script>

<template>
  <div
    :class="[
      'grid items-center text-small',
      stacked ? 'grid-cols-[minmax(0,1fr)_3.5rem] gap-x-3 gap-y-1' : 'grid-cols-[4.5rem_minmax(0,1fr)_3.5rem] gap-3',
    ]"
    data-testid="rating-bar"
  >
    <span
      :class="[stacked ? 'col-span-2 break-keep' : 'truncate', strong ? 'font-bold text-ink' : 'font-normal text-sub']"
      data-testid="rating-bar-label"
    >{{ label }}</span>
    <span class="h-2 overflow-hidden rounded-full bg-surface-muted" aria-hidden="true">
      <span
        :class="['block h-full rounded-full motion-safe:transition-[width] motion-safe:duration-slow motion-safe:ease-soft', strong ? 'bg-primary' : 'bg-(--pastel-lavender-ink)/40']"
        :style="{ width }"
        data-testid="rating-bar-fill"
      ></span>
    </span>
    <span :class="['text-right tabular-nums', strong ? 'font-bold text-ink' : 'font-normal text-sub']">{{ valueText }}</span>
  </div>
</template>
