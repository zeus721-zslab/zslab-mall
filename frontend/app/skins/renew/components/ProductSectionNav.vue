<script setup lang="ts">
import { MessageCircleQuestionMark, X } from '@lucide/vue'
import type { ProductQuestionsVm } from '~/skins/contracts/product-questions'
import type { ProductSectionNavItem, ProductSectionNavVm } from '~/skins/contracts/product-section-nav'
import { PRODUCT_SECTION_NAV_ID, type ProductSectionId } from '~/lib/constants/product-sections'
import { PRODUCT_QUESTION_CONTENT_MAX } from '~/lib/constants/product-question'
import ProductQuestionSuggestions from './ProductQuestionSuggestions.vue'
import RenewNotice from './RenewNotice.vue'

// 진행형 섹션 바(Track 106-2 · 구매 영역 아래 · 상품 설명 위). 스크롤하면 헤더 바로 아래에 붙고(top = 상단 안전 영역 + 헤더 높이 · vm.stickyTop),
// 알약(상품설명 · 리뷰 n · Q&A n)은 현재 섹션에 aria-current를 달고 읽은 비율만큼 배경이 차오른다(동작 줄이기면 차오름 없이 강조만).
// 오른쪽 끝 "물어보기"는 바 안에서 입력창으로 펼쳐지고, 즉시 답 카드·등록 버튼은 바 바로 아래 패널에 뜬다. 입력 초안·즉시 답은 묻기 섹션과
// 같은 vm(페이지의 질문 composable 인스턴스 하나)을 쓴다. Esc·바깥 클릭으로 접히고 초안은 남는다. 펼침 → 입력창, Esc 접힘 → 물어보기 버튼으로
// 포커스를 옮긴다(preventScroll). 우측 하단은 채팅 도우미(106-3) 자리라 비워 둔다.
const props = defineProps<{ nav: ProductSectionNavVm; items: ProductSectionNavItem[]; questions: ProductQuestionsVm | null }>()

const root = ref<HTMLElement | null>(null)
const askButton = ref<HTMLButtonElement | null>(null)
const askInput = ref<HTMLTextAreaElement | null>(null)
const expanded = ref(false)

const hasDraft = computed(() => (props.questions ? props.questions.draft.trim() !== '' : false))
const panelVisible = computed(() => expanded.value && props.questions !== null)

async function expand(): Promise<void> {
  expanded.value = true
  await nextTick()
  askInput.value?.focus({ preventScroll: true })
}

async function collapse(returnFocus: boolean): Promise<void> {
  if (!expanded.value) return
  expanded.value = false
  if (!returnFocus) return
  await nextTick()
  askButton.value?.focus({ preventScroll: true })
}

function onInput(event: Event): void {
  props.questions?.setDraft((event.target as HTMLTextAreaElement).value)
}

function onOutsidePointer(event: PointerEvent): void {
  if (expanded.value && root.value && event.target instanceof Node && !root.value.contains(event.target)) void collapse(false)
}

onMounted(() => document.addEventListener('pointerdown', onOutsidePointer))
onBeforeUnmount(() => document.removeEventListener('pointerdown', onOutsidePointer))

function progressOf(id: ProductSectionId): number {
  return props.nav.progress[id] ?? 0
}
</script>

<template>
  <nav
    :id="PRODUCT_SECTION_NAV_ID"
    ref="root"
    aria-label="상품 정보 바로가기"
    class="sticky z-40 -mx-5 mt-16 bg-surface-page/95 px-5 py-2 backdrop-blur-sm md:mx-0 md:px-0"
    :style="{ top: `calc(env(safe-area-inset-top, 0px) + ${nav.stickyTop}px)` }"
    data-testid="product-section-nav"
    @keydown.esc="collapse(true)"
  >
    <div class="flex items-center gap-2">
      <ul :class="['flex min-w-0 flex-1 gap-2 overflow-x-auto', expanded ? 'max-md:hidden' : '']" data-testid="section-nav-items">
        <li v-for="item in items" :key="item.id" class="shrink-0">
          <button
            type="button"
            :aria-current="nav.activeId === item.id ? 'true' : undefined"
            :class="[
              'relative inline-flex min-h-11 items-center overflow-hidden rounded-full border px-4 text-small font-semibold transition duration-fast ease-soft focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-(--ring) md:min-h-9',
              nav.activeId === item.id ? 'border-primary text-primary' : 'border-line bg-white text-ink hover:border-ink',
            ]"
            :data-testid="`section-nav-${item.id}`"
            @click="nav.go(item.id)"
          >
            <!-- 읽은 비율만큼 차오르는 배경 · 동작 줄이기면 숨긴다(활성 강조만 남는다) -->
            <span
              class="absolute inset-y-0 left-0 bg-(--pastel-lavender-bg) transition-[width] duration-fast ease-soft motion-reduce:hidden"
              :style="{ width: `${Math.round(progressOf(item.id) * 100)}%` }"
              aria-hidden="true"
              data-testid="section-nav-progress"
            ></span>
            <span class="relative">{{ item.label }}</span>
          </button>
        </li>
      </ul>

      <template v-if="questions">
        <div v-if="expanded" class="flex min-w-0 flex-1 items-center gap-2 md:max-w-md">
          <label for="product-question-bar-input" class="sr-only">상품에 대해 궁금한 점</label>
          <textarea
            id="product-question-bar-input"
            ref="askInput"
            :value="questions.draft"
            :maxlength="PRODUCT_QUESTION_CONTENT_MAX"
            rows="1"
            aria-controls="product-section-nav-panel"
            class="min-h-11 flex-1 resize-none rounded-full border border-primary bg-white px-4 py-2.5 text-body text-ink placeholder:text-sub focus:outline-hidden focus:ring-1 focus:ring-primary md:min-h-9 md:py-1.5"
            placeholder="예: 세탁기에 돌려도 되나요?"
            data-testid="section-nav-ask-input"
            @input="onInput"
          ></textarea>
          <button
            type="button"
            class="flex h-11 w-11 shrink-0 items-center justify-center rounded-full text-sub hover:bg-white focus-visible:outline-2 focus-visible:outline-(--ring) md:h-9 md:w-9"
            aria-label="물어보기 접기"
            data-testid="section-nav-ask-close"
            @click="collapse(true)"
          >
            <X class="h-4 w-4" aria-hidden="true" />
          </button>
        </div>
        <button
          v-else
          ref="askButton"
          type="button"
          class="inline-flex min-h-11 shrink-0 items-center gap-1.5 rounded-full bg-primary px-4 text-small font-semibold text-white focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-(--ring) md:min-h-9"
          aria-expanded="false"
          aria-controls="product-section-nav-panel"
          data-testid="section-nav-ask"
          @click="expand()"
        >
          <MessageCircleQuestionMark class="h-4 w-4" aria-hidden="true" />물어보기
        </button>
      </template>
    </div>

    <!-- 물어보기 패널: 바 바로 아래 · 최대 높이 + 내부 스크롤 · ≥1024는 입력창 아래 오른쪽 정렬 최대 640px(전체 폭이면 카드가 과하게 늘어난다) -->
    <div
      v-if="panelVisible && questions && (hasDraft || questions.notice)"
      id="product-section-nav-panel"
      class="absolute inset-x-0 top-full mt-1 max-h-[60vh] overflow-y-auto rounded-card bg-white p-4 shadow-e2 md:p-5 lg:left-auto lg:w-full lg:max-w-[640px]"
      data-testid="section-nav-panel"
    >
      <ProductQuestionSuggestions v-if="questions.suggestions.length > 0" :suggestions="questions.suggestions" />
      <div v-if="hasDraft" class="mt-3 flex flex-col items-start gap-2">
        <button
          type="button"
          class="btn btn-primary btn-md"
          :disabled="questions.submitting"
          :aria-busy="questions.submitting"
          data-testid="section-nav-submit"
          @click="questions.submit()"
        >
          {{ questions.submitting ? '남기는 중…' : '해결되지 않았어요 → 셀러에게 질문 남기기' }}
        </button>
        <p class="text-caption font-normal text-sub">질문은 공개로 등록돼요. 답변은 마이페이지 "내 질문"에서 확인할 수 있어요.</p>
      </div>
      <div v-if="questions.notice" class="mt-3">
        <RenewNotice :tone="questions.notice.tone">{{ questions.notice.text }}</RenewNotice>
      </div>
    </div>
  </nav>
</template>
