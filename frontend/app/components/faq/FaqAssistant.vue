<script setup lang="ts">
import { MessageCircleMore, X } from '@lucide/vue'
import { faqFirstCategories, isFaqAssistantExcluded } from '~/lib/constants/faq'
import type { FaqItem } from '~/types/faq'
import FaqAssistantConversation from './FaqAssistantConversation.vue'
import FaqAssistantInput from './FaqAssistantInput.vue'

// 구매자 채팅 도우미(Track 106-3): 우측 하단 버튼 + 비모달 패널. 구매자 레이아웃(default.vue)에 <ClientOnly>로 붙어 SSR에서는 그리지 않는다.
// 비모달이라 패널을 연 채 뒤 화면을 조작하고 링크로 이동할 수 있다(포커스 가두기 없음 · Esc·닫기로 닫힘 · 닫으면 버튼으로 포커스 복귀).
// 1024px 미만에서 하단 고정 바(MobileActionBar)가 보이면 버튼을 바 위로 올린다 — 바가 body에 다는 data-mobile-action-bar 속성을 CSS로 읽는다.
const route = useRoute()
const assistant = useFaqAssistant()

const excluded = computed(() => isFaqAssistantExcluded(route.path))
const firstCategories = computed(() => faqFirstCategories(route.path))

const launcher = ref<HTMLButtonElement | null>(null)
const panel = ref<HTMLElement | null>(null)

onMounted(() => {
  assistant.restore()
  assistant.persist()
})

async function openPanel(): Promise<void> {
  await assistant.openPanel()
}

async function closePanel(): Promise<void> {
  assistant.closePanel()
  await nextTick()
  launcher.value?.focus()
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') void closePanel()
}

function pickSuggestion(faq: FaqItem): void {
  assistant.clearSuggestions()
  void assistant.selectChip({ kind: 'question', faq })
}

// 열리면 패널로 포커스를 옮겨 스크린리더가 대화 영역을 읽게 한다.
watch(() => assistant.open.value, async (isOpen) => {
  if (!isOpen) return
  await nextTick()
  panel.value?.focus()
})
</script>

<template>
  <template v-if="!excluded">
    <button
      v-show="!assistant.open.value"
      ref="launcher"
      type="button"
      class="faq-launcher fixed bottom-[calc(16px+env(safe-area-inset-bottom,0px))] right-4 z-45 flex h-14 w-14 items-center justify-center rounded-full bg-primary text-white shadow-e2 transition-[bottom] duration-normal ease-soft focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-(--ring) md:bottom-6 md:right-6"
      aria-label="자주 묻는 질문 도우미 열기"
      aria-controls="faq-assistant-panel"
      :aria-expanded="assistant.open.value"
      data-testid="faq-launcher"
      @click="openPanel"
    >
      <MessageCircleMore class="h-6 w-6" aria-hidden="true" />
    </button>

    <section
      v-if="assistant.open.value"
      id="faq-assistant-panel"
      ref="panel"
      tabindex="-1"
      class="fixed inset-x-0 bottom-0 z-45 flex max-h-[75dvh] flex-col rounded-t-card bg-white pb-[env(safe-area-inset-bottom,0px)] shadow-e3 outline-hidden md:inset-x-auto md:bottom-6 md:right-6 md:max-h-[min(640px,calc(100dvh-96px))] md:w-[380px] md:rounded-card md:pb-0"
      aria-labelledby="faq-assistant-title"
      data-testid="faq-panel"
      @keydown="onKeydown"
    >
      <header class="flex items-center justify-between gap-3 border-b border-line px-4 py-3">
        <div class="flex items-center gap-2">
          <span class="flex h-8 w-8 items-center justify-center rounded-full bg-surface-muted text-primary">
            <MessageCircleMore class="h-4 w-4" aria-hidden="true" />
          </span>
          <h2 id="faq-assistant-title" class="text-body font-semibold text-ink">무엇이든 물어보세요</h2>
        </div>
        <button
          type="button"
          class="flex h-11 w-11 items-center justify-center rounded-full text-sub hover:bg-surface-muted focus-visible:outline-2 focus-visible:outline-(--ring) md:h-9 md:w-9"
          aria-label="도우미 닫기"
          data-testid="faq-close"
          @click="closePanel"
        >
          <X class="h-4 w-4" aria-hidden="true" />
        </button>
      </header>
      <FaqAssistantConversation
        :messages="assistant.messages.value"
        :typing="assistant.typing.value"
        :first-categories="firstCategories"
        @chip="assistant.selectChip"
        @category="assistant.selectCategory"
      />
      <FaqAssistantInput
        :suggestions="assistant.suggestions.value"
        :disabled="assistant.typing.value"
        @input="assistant.onInput"
        @submit="assistant.submitText"
        @pick="pickSuggestion"
      />
    </section>
  </template>
</template>

<style scoped>
/* 1024px 미만 하단 고정 바(MobileActionBar · 높이 73px + 안전 영역)가 보이는 동안 버튼을 바 위 16px로 올린다.
   :global(부모) 자식 형태는 Vue가 자식 선택자를 버리므로 선택자 전체를 :global로 감싼다(.faq-launcher는 이 컴포넌트 전용 클래스). */
@media (width < 64rem) {
  :global(body[data-mobile-action-bar='shown'] .faq-launcher) {
    bottom: calc(89px + env(safe-area-inset-bottom, 0px));
  }
}
</style>
