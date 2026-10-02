<script setup lang="ts">
import type { ManualCallout, ManualCaptureMeta } from '~/types/manual'
import { markerPosition, paddedRegion, separateMarkers } from '~/lib/utils/manual'

/**
 * 매뉴얼 캡처 1장 + 영역 표시(C8). 이미지 위에 이미지 픽셀 좌표계(viewBox)의 SVG를 같은 크기로 겹쳐 하이라이트를 그리고,
 * 번호 마커는 비율(%) 위치의 버튼으로 둔다 — 이미지가 어떤 폭으로 그려져도 같은 자리에 겹친다.
 * 활성 번호가 있으면 그 영역만 밝게 남기고 나머지를 어둡게 덮는다(스포트라이트). 본문·확대 보기가 같은 컴포넌트를 쓴다.
 */
const props = defineProps<{
  capture: ManualCaptureMeta
  src: string
  alt: string
  callouts: ManualCallout[]
  active: number | null
}>()
const emit = defineEmits<{ 'update:active': [number: number | null]; open: [] }>()

const maskId = useId()

interface PlacedCallout {
  callout: ManualCallout
  x: number
  y: number
  width: number
  height: number
  left: number
  top: number
}

// 캡처에 영역이 없는 콜아웃(콘텐츠·캡처 불일치)은 마커를 그리지 않는다 — 범례에는 남는다.
// 붙어 있는 영역의 마커는 separateMarkers로 표시 위치만 밀어낸다(하이라이트 영역은 원래 좌표).
const placed = computed<PlacedCallout[]>(() => {
  const { width, height } = props.capture
  const located = props.callouts.flatMap((callout) => {
    const region = props.capture.regions[callout.region]
    if (region === undefined) return []
    const point = markerPosition(region, width, height)
    return [{ callout, box: paddedRegion(region, width, height), x: (point.left / 100) * width, y: (point.top / 100) * height }]
  })
  const separated = separateMarkers(located.map((item) => ({ number: item.callout.number, x: item.x, y: item.y })), width, height)
  return located.map((item) => {
    const marker = separated.find((point) => point.number === item.callout.number) ?? { x: item.x, y: item.y }
    return { callout: item.callout, ...item.box, left: (marker.x / width) * 100, top: (marker.y / height) * 100 }
  })
})

const activePlaced = computed<PlacedCallout | null>(() => placed.value.find((item) => item.callout.number === props.active) ?? null)
</script>

<template>
  <div
    class="manual-frame"
    :class="{ 'manual-frame--spotlight': activePlaced !== null }"
    :style="{ aspectRatio: `${capture.width} / ${capture.height}` }"
    data-testid="manual-capture-frame"
  >
    <img :src="src" :alt="alt" :width="capture.width" :height="capture.height" decoding="async" class="manual-frame__image" @click="emit('open')">
    <svg class="manual-frame__overlay" :viewBox="`0 0 ${capture.width} ${capture.height}`" preserveAspectRatio="none" aria-hidden="true">
      <defs>
        <mask :id="maskId">
          <rect x="0" y="0" :width="capture.width" :height="capture.height" fill="white" />
          <rect
            v-if="activePlaced"
            :x="activePlaced.x"
            :y="activePlaced.y"
            :width="activePlaced.width"
            :height="activePlaced.height"
            rx="10"
            fill="black"
          />
        </mask>
      </defs>
      <rect class="manual-frame__dim" x="0" y="0" :width="capture.width" :height="capture.height" :mask="`url(#${maskId})`" />
      <rect
        v-for="item in placed"
        :key="item.callout.number"
        class="manual-frame__region"
        :class="{ 'manual-frame__region--active': item.callout.number === active }"
        :x="item.x"
        :y="item.y"
        :width="item.width"
        :height="item.height"
        rx="10"
        vector-effect="non-scaling-stroke"
        :data-testid="`manual-region-${item.callout.number}`"
      />
    </svg>
    <button
      v-for="item in placed"
      :key="item.callout.number"
      type="button"
      class="manual-pin"
      :class="{ 'manual-pin--active': item.callout.number === active }"
      :style="{ left: `${item.left}%`, top: `${item.top}%` }"
      :aria-label="`${item.callout.number}번 ${item.callout.label}`"
      :title="item.callout.label"
      :data-testid="`manual-pin-${item.callout.number}`"
      @mouseenter="emit('update:active', item.callout.number)"
      @focus="emit('update:active', item.callout.number)"
      @mouseleave="emit('update:active', null)"
      @blur="emit('update:active', null)"
    >
      {{ item.callout.number }}
    </button>
  </div>
</template>

<style scoped>
.manual-frame {
  position: relative;
  width: 100%;
  overflow: hidden;
  border-radius: 10px;
  background: #e4e4e7;
  box-shadow: 0 0 0 1px rgba(24, 24, 27, 0.08), 0 18px 40px -24px rgba(24, 24, 27, 0.35);
}

.manual-frame__image {
  display: block;
  width: 100%;
  height: 100%;
  cursor: zoom-in;
}

.manual-frame__overlay {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
}

.manual-frame__dim {
  fill: rgba(9, 9, 11, 0.58);
  opacity: 0;
  transition: opacity 180ms ease;
}

.manual-frame--spotlight .manual-frame__dim {
  opacity: 1;
}

.manual-frame__region {
  fill: rgba(var(--v-theme-primary), 0.06);
  stroke: rgb(var(--v-theme-primary));
  stroke-width: 2;
  stroke-dasharray: 6 4;
}

.manual-frame__region--active {
  fill: transparent;
  stroke-width: 3;
  stroke-dasharray: none;
}

.manual-frame--spotlight .manual-frame__region:not(.manual-frame__region--active) {
  opacity: 0;
}

.manual-pin {
  position: absolute;
  width: 26px;
  height: 26px;
  margin: -13px 0 0 -13px;
  border-radius: 50%;
  background: rgb(var(--v-theme-primary));
  color: #fff;
  font-size: 13px;
  font-weight: 700;
  line-height: 26px;
  text-align: center;
  box-shadow: 0 0 0 2px #fff, 0 2px 6px rgba(9, 9, 11, 0.35);
  transition: transform 140ms ease;
}

.manual-pin--active,
.manual-pin:focus-visible {
  transform: scale(1.18);
}

.manual-pin:focus-visible {
  outline: 3px solid #fff;
  outline-offset: 2px;
}

@media (prefers-reduced-motion: reduce) {
  .manual-frame__dim,
  .manual-pin {
    transition: none;
  }
}
</style>
