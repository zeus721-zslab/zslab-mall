<script setup lang="ts">
import type { ManualCaptureIndex, ManualDocument } from '~/types/manual'
import { manualAnchorId, resolveManualHash } from '~/lib/utils/manual'
import { resolveActiveSection, type SectionBox } from '~/lib/utils/section-nav'
import ManualStep from '~/components/common/manual/ManualStep.vue'
import ManualToc from '~/components/common/manual/ManualToc.vue'

/**
 * 관리자·셀러 웹 매뉴얼 셸(C8). 왼쪽 흐름 목차 + 본문(흐름 → 단계). 목차는 스크롤 위치를 따라 현재 단계를 표시하고(section-nav 재사용),
 * 클릭하면 해당 단계로 이동하며 URL 해시를 갱신한다 — 해시가 붙은 주소로 들어오면 그 단계에서 시작한다.
 * 색은 역할 Vuetify 테마 primary(관리자·셀러 vuetify.ts)를 그대로 쓴다.
 */
const props = defineProps<{ document: ManualDocument; captures: ManualCaptureIndex }>()

/** 상단바(64px) 아래로 단계 제목이 보이도록 스크롤 정지 위치를 내린다. 본문 scroll-margin-top과 같은 값. */
const SCROLL_OFFSET_PX = 88

const route = useRoute()
const activeId = ref<string | null>(null)

const writtenSections = computed(() => props.document.sections.filter((section) => section.steps.length > 0))
const anchorIds = computed<string[]>(() => writtenSections.value.flatMap((section) => [
  manualAnchorId(section.id),
  ...section.steps.map((step) => manualAnchorId(section.id, step.id)),
]))

function prefersReducedMotion(): boolean {
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

function updateActive(): void {
  const boxes: SectionBox[] = []
  for (const id of anchorIds.value) {
    const element = document.getElementById(id)
    if (element === null) continue
    const rect = element.getBoundingClientRect()
    boxes.push({ id, top: rect.top, height: rect.height })
  }
  const atPageEnd = window.innerHeight + window.scrollY >= document.documentElement.scrollHeight - 2
  activeId.value = resolveActiveSection(boxes, SCROLL_OFFSET_PX + 8, window.innerHeight, atPageEnd)
}

/**
 * 목차 클릭 이동 중에는 스크롤 위치 감지를 멈추고 활성 표시를 대상에 고정한다 — 부드러운 스크롤이 지나가는 단계마다 표시가 옮겨 다니지 않게.
 * scrollend(지원 브라우저)가 오거나, 스크롤 이벤트가 이 시간 동안 없으면(미지원 브라우저·이미 그 자리라 스크롤이 없는 경우) 감지를 재개한다.
 */
const CLICK_SCROLL_QUIET_MS = 200

let clickLocked = false
let quietTimer = 0

function releaseClickLock(): void {
  clickLocked = false
  window.clearTimeout(quietTimer)
  quietTimer = 0
  window.removeEventListener('scrollend', releaseClickLock)
}

function restartQuietTimer(): void {
  window.clearTimeout(quietTimer)
  quietTimer = window.setTimeout(releaseClickLock, CLICK_SCROLL_QUIET_MS)
}

function holdActiveWhileScrolling(anchorId: string): void {
  releaseClickLock()
  clickLocked = true
  // 클릭 직전 스크롤로 예약된 감지 프레임이 잠금 뒤에 실행돼 표시를 덮어쓰지 않게 취소한다.
  if (frame !== 0) {
    window.cancelAnimationFrame(frame)
    frame = 0
  }
  activeId.value = anchorId
  window.addEventListener('scrollend', releaseClickLock, { once: true })
  restartQuietTimer()
}

let frame = 0
function onScroll(): void {
  if (clickLocked) {
    restartQuietTimer()
    return
  }
  if (frame !== 0) return
  frame = window.requestAnimationFrame(() => {
    frame = 0
    updateActive()
  })
}

function scrollToAnchor(anchorId: string, smooth: boolean): void {
  const element = document.getElementById(anchorId)
  if (element === null) return
  element.scrollIntoView({ behavior: smooth && !prefersReducedMotion() ? 'smooth' : 'auto', block: 'start' })
  // 키보드 사용자가 이동한 단계에서 이어 읽도록 포커스를 옮긴다(스크롤은 위에서 이미 했다).
  element.focus({ preventScroll: true })
}

/** 목차 클릭: 이동 + 해시 갱신. 라우터 이동을 쓰면 Nuxt 스크롤 동작이 겹치므로 history만 바꾼다. */
function go(anchorId: string): void {
  holdActiveWhileScrolling(anchorId)
  scrollToAnchor(anchorId, true)
  window.history.replaceState(window.history.state, '', `#${anchorId}`)
}

function enterFromHash(hash: string): void {
  const target = resolveManualHash(hash, props.document.sections)
  if (target !== null) scrollToAnchor(target, false)
}

onMounted(async () => {
  await nextTick()
  enterFromHash(route.hash)
  updateActive()
  window.addEventListener('scroll', onScroll, { passive: true })
  window.addEventListener('resize', onScroll, { passive: true })
})

onBeforeUnmount(() => {
  window.removeEventListener('scroll', onScroll)
  window.removeEventListener('resize', onScroll)
  if (frame !== 0) window.cancelAnimationFrame(frame)
  releaseClickLock()
})

// 뒤로가기·앞으로가기로 해시만 바뀌는 경우.
watch(() => route.hash, (hash) => enterFromHash(hash))
</script>

<template>
  <div class="manual" :style="{ '--manual-scroll-offset': `${SCROLL_OFFSET_PX}px` }" data-testid="manual-shell">
    <aside class="manual__toc">
      <ManualToc :sections="document.sections" :active-id="activeId" @go="go" />
    </aside>

    <div class="manual__paper">
      <header class="manual__header">
        <h1 class="manual__title" data-testid="manual-title">{{ document.title }}</h1>
        <p class="manual__intro">{{ document.intro }}</p>
      </header>

      <section
        v-for="section in writtenSections"
        :id="manualAnchorId(section.id)"
        :key="section.id"
        class="manual__section"
        tabindex="-1"
        :data-testid="`manual-section-${section.id}`"
      >
        <h2 class="manual__section-title">{{ section.title }}</h2>
        <p class="manual__section-summary">{{ section.summary }}</p>
        <div class="manual__steps">
          <ManualStep
            v-for="(step, index) in section.steps"
            :key="step.id"
            :role="document.role"
            :anchor-id="manualAnchorId(section.id, step.id)"
            :order="index + 1"
            :step="step"
            :capture="step.captureId === null ? null : (captures[step.captureId] ?? null)"
          />
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.manual {
  display: grid;
  grid-template-columns: 232px minmax(0, 1fr);
  gap: 20px;
  align-items: start;
  color: #18181b;
}

.manual__toc {
  position: sticky;
  top: var(--manual-scroll-offset);
  max-height: calc(100vh - var(--manual-scroll-offset) - 16px);
  overflow-y: auto;
  padding: 20px 16px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.86);
  backdrop-filter: blur(8px);
  box-shadow: 0 0 0 1px rgba(24, 24, 27, 0.06);
}

.manual__paper {
  padding: 40px 40px 24px;
  border-radius: 16px;
  background: #fff;
  box-shadow: 0 0 0 1px rgba(24, 24, 27, 0.06), 0 24px 48px -32px rgba(24, 24, 27, 0.28);
}

.manual__header {
  max-width: 68ch;
  margin-bottom: 40px;
}

.manual__title {
  margin: 0 0 10px;
  font-size: 30px;
  font-weight: 750;
  letter-spacing: -0.02em;
  line-height: 38px;
}

.manual__intro {
  margin: 0;
  color: #52525b;
  font-size: 15.5px;
  line-height: 27px;
}

.manual__section {
  scroll-margin-top: var(--manual-scroll-offset);
}

.manual__section:focus {
  outline: none;
}

.manual__section + .manual__section {
  margin-top: 24px;
  padding-top: 40px;
  border-top: 1px solid #e4e4e7;
}

.manual__section-title {
  margin: 0 0 8px;
  font-size: 23px;
  font-weight: 750;
  letter-spacing: -0.015em;
  line-height: 32px;
}

.manual__section-summary {
  max-width: 68ch;
  margin: 0 0 32px;
  color: #52525b;
  font-size: 15px;
  line-height: 26px;
}

@media (max-width: 959px) {
  .manual {
    grid-template-columns: minmax(0, 1fr);
  }

  .manual__toc {
    position: static;
    max-height: none;
  }

  .manual__paper {
    padding: 28px 18px 8px;
  }
}
</style>
