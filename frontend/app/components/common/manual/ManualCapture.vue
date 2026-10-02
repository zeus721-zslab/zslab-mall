<script setup lang="ts">
import type { ManualCallout, ManualCaptureMeta, ManualRole } from '~/types/manual'
import ManualCaptureFrame from '~/components/common/manual/ManualCaptureFrame.vue'

/**
 * 매뉴얼 단계 캡처(C8): 영역 표시 이미지 + 번호 설명 목록 + 확대 보기. 마커와 설명 항목은 같은 활성 번호를 공유해
 * 어느 쪽에 마우스·키보드 포커스를 두어도 서로 강조된다. 캡처가 아직 없으면 자리 안내만 두고 설명 목록은 그대로 보인다.
 */
const props = defineProps<{
  role: ManualRole
  capture: ManualCaptureMeta | null
  alt: string
  callouts: ManualCallout[]
}>()

const active = ref<number | null>(null)
const zoomOpen = ref(false)

const src = computed<string>(() => props.capture === null ? '' : `/manual/${props.role}/${props.capture.file}?v=${props.capture.hash}`)
const activeCallout = computed<ManualCallout | null>(() => props.callouts.find((callout) => callout.number === active.value) ?? null)
</script>

<template>
  <figure class="manual-capture" data-testid="manual-capture">
    <!-- 원본보다 크게 늘리면 글자가 흐려진다 — 다이얼로그만 자른 작은 캡처는 원본 폭에서 멈춘다. -->
    <div v-if="capture" class="manual-capture__stage" :style="{ maxWidth: `${capture.width}px` }">
      <ManualCaptureFrame v-model:active="active" :capture="capture" :src="src" :alt="alt" :callouts="callouts" @open="zoomOpen = true" />
      <button type="button" class="manual-capture__zoom" data-testid="manual-capture-zoom" @click="zoomOpen = true">크게 보기</button>
    </div>
    <div v-else class="manual-capture__pending" data-testid="manual-capture-pending">이 단계의 화면 캡처는 준비 중입니다.</div>

    <ol v-if="callouts.length > 0" class="manual-legend" :aria-label="`${alt} 영역 설명`">
      <li v-for="callout in callouts" :key="callout.number">
        <button
          type="button"
          class="manual-legend__item"
          :class="{ 'manual-legend__item--active': callout.number === active }"
          :data-testid="`manual-legend-${callout.number}`"
          @mouseenter="active = callout.number"
          @focus="active = callout.number"
          @mouseleave="active = null"
          @blur="active = null"
        >
          <span class="manual-legend__number" aria-hidden="true">{{ callout.number }}</span>
          <span class="manual-legend__text">
            <span class="manual-legend__label">{{ callout.label }}</span>
            <span class="manual-legend__description">{{ callout.description }}</span>
          </span>
        </button>
      </li>
    </ol>

    <v-dialog v-if="capture" v-model="zoomOpen" :max-width="capture.width + 32" scrollable>
      <div class="manual-zoom" data-testid="manual-capture-zoom-dialog">
        <div class="manual-zoom__bar">
          <p class="manual-zoom__caption" aria-live="polite">
            <template v-if="activeCallout"><strong>{{ activeCallout.number }}. {{ activeCallout.label }}</strong> {{ activeCallout.description }}</template>
            <template v-else>번호에 마우스를 올리거나 Tab으로 옮기면 그 영역만 밝게 보입니다.</template>
          </p>
          <button type="button" class="manual-zoom__close" data-testid="manual-capture-zoom-close" @click="zoomOpen = false">닫기</button>
        </div>
        <ManualCaptureFrame v-model:active="active" :capture="capture" :src="src" :alt="alt" :callouts="callouts" />
      </div>
    </v-dialog>
  </figure>
</template>

<style scoped>
.manual-capture {
  margin: 20px 0 8px;
}

.manual-capture__stage {
  position: relative;
}

.manual-capture__zoom {
  position: absolute;
  top: 12px;
  right: 12px;
  padding: 6px 12px;
  border-radius: 999px;
  background: rgba(9, 9, 11, 0.72);
  color: #fff;
  font-size: 12px;
  font-weight: 600;
}

.manual-capture__zoom:focus-visible {
  outline: 3px solid rgb(var(--v-theme-primary));
  outline-offset: 2px;
}

.manual-capture__pending {
  display: grid;
  place-items: center;
  aspect-ratio: 16 / 7;
  border: 1.5px dashed #a1a1aa;
  border-radius: 10px;
  color: #52525b;
  font-size: 14px;
}

.manual-legend {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 4px 20px;
  margin: 16px 0 0;
  padding: 0;
  list-style: none;
}

.manual-legend__item {
  display: flex;
  gap: 12px;
  width: 100%;
  padding: 10px 10px 10px 8px;
  border-radius: 8px;
  text-align: left;
  transition: background-color 140ms ease;
}

.manual-legend__item--active,
.manual-legend__item:focus-visible {
  background: rgba(var(--v-theme-primary), 0.08);
}

.manual-legend__item:focus-visible {
  outline: 2px solid rgb(var(--v-theme-primary));
  outline-offset: 0;
}

.manual-legend__number {
  flex: none;
  width: 22px;
  height: 22px;
  margin-top: 1px;
  border-radius: 50%;
  background: rgb(var(--v-theme-primary));
  color: #fff;
  font-size: 12px;
  font-weight: 700;
  line-height: 22px;
  text-align: center;
}

.manual-legend__text {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.manual-legend__label {
  color: #18181b;
  font-size: 14px;
  font-weight: 650;
  line-height: 22px;
}

.manual-legend__description {
  color: #52525b;
  font-size: 13.5px;
  line-height: 21px;
}

.manual-zoom {
  overflow: auto;
  padding: 16px;
  border-radius: 14px;
  background: #fff;
}

.manual-zoom__bar {
  display: flex;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 12px;
}

.manual-zoom__caption {
  flex: 1;
  min-height: 22px;
  margin: 0;
  color: #3f3f46;
  font-size: 14px;
  line-height: 22px;
}

.manual-zoom__close {
  flex: none;
  padding: 4px 14px;
  border: 1px solid #d4d4d8;
  border-radius: 999px;
  font-size: 13px;
  font-weight: 600;
}

.manual-zoom__close:focus-visible {
  outline: 2px solid rgb(var(--v-theme-primary));
  outline-offset: 2px;
}

@media (prefers-reduced-motion: reduce) {
  .manual-legend__item {
    transition: none;
  }
}
</style>
