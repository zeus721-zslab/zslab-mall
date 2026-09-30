import {
  FAQ_CONVERSATION_MAX_MESSAGES,
  FAQ_CONVERSATION_STORAGE_KEY,
  FAQ_TYPING_DELAY_MS,
  type FaqCategory,
  faqCategoryLabel,
} from '~/lib/constants/faq'
import { inquiryNewPath, orderIdFromPath } from '~/lib/constants/inquiry'
import type { FaqItem } from '~/types/faq'

/** 말풍선 아래 칩. 카테고리 칩 묶음은 말풍선의 showCategories로 따로 그린다(현재 페이지 기준으로 순서·강조가 바뀌어야 해서 저장하지 않는다). */
export type FaqChip =
  | { kind: 'question'; faq: FaqItem }
  | { kind: 'moreInCategory'; category: FaqCategory; excludeId: number }
  | { kind: 'restart' }
  | { kind: 'retry' }
  /** 운영자에게 남기기(Track 106-4) — 누르는 시점의 경로로 작성 화면 주소를 만든다(주문 상세면 그 주문을 붙임). */
  | { kind: 'inquiry' }

export interface FaqMessage {
  id: number
  role: 'assistant' | 'user'
  text: string
  /** 도우미 답(FAQ 답변) 말풍선 — 여러 줄 본문 스타일. */
  isAnswer?: boolean
  isError?: boolean
  showCategories?: boolean
  chips: FaqChip[]
}

interface StoredConversation {
  open: boolean
  messages: FaqMessage[]
}

const GREETING = '안녕하세요! 무엇을 도와드릴까요? 궁금한 주제를 고르거나 아래에 직접 물어보세요.'
const RESTART_TEXT = '다른 궁금한 점을 골라 주세요.'
const LOAD_ERROR_TEXT = '자주 묻는 질문을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.'
const EMPTY_LIST_TEXT = '아직 등록된 자주 묻는 질문이 없어요.'
const EMPTY_CATEGORY_TEXT = '이 주제에는 아직 등록된 질문이 없어요. 다른 주제를 골라 주세요.'
const NOT_FOUND_TEXT = '찾지 못했어요. 아래 주제에서 찾아보시겠어요?'
const SEARCH_ERROR_TEXT = '지금은 검색하지 못했어요. 잠시 후 다시 시도하거나 아래 주제에서 찾아보세요.'

function isStoredMessage(value: unknown): value is FaqMessage {
  if (typeof value !== 'object' || value === null) return false
  const message = value as Partial<FaqMessage>
  return typeof message.id === 'number' && (message.role === 'assistant' || message.role === 'user')
    && typeof message.text === 'string' && Array.isArray(message.chips)
}

function prefersReducedMotion(): boolean {
  return typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

/**
 * 채팅 도우미 대화(Track 106-3). 인사 → 카테고리 칩 → 질문 칩 → 답 · 자유 입력 검색. 대화와 패널 열림 상태는 탭 동안 sessionStorage에 두어
 * 페이지를 옮기거나 새로고침해도 이어진다(최근 50개 말풍선). 상태는 앱 전역(useState)이라 레이아웃이 다시 그려져도 유지된다.
 */
export function useFaqAssistant() {
  const route = useRoute()
  const faqList = useFaqList()
  const faqSuggest = useFaqSuggest()
  const messages = useState<FaqMessage[]>('faq-assistant-messages', () => [])
  const open = useState<boolean>('faq-assistant-open', () => false)
  const typing = useState<boolean>('faq-assistant-typing', () => false)

  function push(message: Omit<FaqMessage, 'id'>): void {
    const nextId = messages.value.reduce((max, item) => Math.max(max, item.id), 0) + 1
    messages.value = [...messages.value, { ...message, id: nextId }].slice(-FAQ_CONVERSATION_MAX_MESSAGES)
  }

  function say(text: string, extra: Partial<Omit<FaqMessage, 'id' | 'role' | 'text'>> = {}): void {
    push({ role: 'assistant', text, chips: [], ...extra })
  }

  async function typingPause(): Promise<void> {
    if (prefersReducedMotion()) return
    await new Promise((resolve) => setTimeout(resolve, FAQ_TYPING_DELAY_MS))
  }

  /** 목록을 준비한다(받는 동안 타이핑 표시). 실패면 오류 말풍선 + 다시 시도 칩을 남기고 false. */
  async function ensureList(): Promise<boolean> {
    if (faqList.status.value !== 'ready') {
      typing.value = true
      await faqList.load()
      typing.value = false
    }
    if (faqList.status.value === 'error') {
      say(LOAD_ERROR_TEXT, { isError: true, chips: [{ kind: 'retry' }] })
      return false
    }
    return true
  }

  async function greet(): Promise<void> {
    if (!(await ensureList())) return
    if (faqList.items.value.length === 0) {
      say(EMPTY_LIST_TEXT)
      return
    }
    say(GREETING, { showCategories: true })
  }

  function questionChips(category: FaqCategory, excludeId?: number): FaqChip[] {
    return faqList.items.value
      .filter((faq) => faq.category === category && faq.id !== excludeId)
      .map((faq) => ({ kind: 'question', faq }))
  }

  async function showCategory(category: FaqCategory, userText: string, excludeId?: number): Promise<void> {
    push({ role: 'user', text: userText, chips: [] })
    if (!(await ensureList())) return
    const chips = questionChips(category, excludeId)
    if (chips.length === 0) {
      say(EMPTY_CATEGORY_TEXT, { showCategories: true })
      return
    }
    say(`${faqCategoryLabel(category)} 관련 자주 묻는 질문이에요.`, { chips })
  }

  async function showAnswer(faq: FaqItem): Promise<void> {
    push({ role: 'user', text: faq.question, chips: [] })
    typing.value = true
    await typingPause()
    typing.value = false
    say(faq.answer, {
      isAnswer: true,
      chips: [{ kind: 'moreInCategory', category: faq.category, excludeId: faq.id }, { kind: 'inquiry' }, { kind: 'restart' }],
    })
  }

  /** 대화가 비어 있으면 인사부터 시작한다. */
  async function openPanel(): Promise<void> {
    open.value = true
    if (messages.value.length === 0) await greet()
  }

  function closePanel(): void {
    open.value = false
  }

  async function selectChip(chip: FaqChip): Promise<void> {
    if (typing.value) return
    switch (chip.kind) {
      case 'question':
        await showAnswer(chip.faq)
        return
      case 'moreInCategory':
        await showCategory(chip.category, `${faqCategoryLabel(chip.category)} 다른 질문`, chip.excludeId)
        return
      case 'restart':
        push({ role: 'user', text: '처음으로', chips: [] })
        say(RESTART_TEXT, { showCategories: true })
        return
      case 'retry':
        await greet()
        return
      case 'inquiry':
        // 로그인 여부는 여기서 보지 않는다 — 작성 화면의 buyer 미들웨어가 로그인 후 이 주소로 돌려보낸다.
        await navigateTo(inquiryNewPath(orderIdFromPath(route.path)))
    }
  }

  async function selectCategory(category: FaqCategory): Promise<void> {
    if (typing.value) return
    await showCategory(category, faqCategoryLabel(category))
  }

  /** 자유 입력 전송: 검색 결과를 질문 칩으로 보여 준다(0건·실패면 카테고리 칩으로 안내). */
  async function submitText(text: string): Promise<void> {
    const trimmed = text.trim()
    if (typing.value || trimmed.length === 0) return
    push({ role: 'user', text: trimmed, chips: [] })
    typing.value = true
    const results = await faqSuggest.search(trimmed)
    typing.value = false
    if (results === null) {
      say(SEARCH_ERROR_TEXT, { isError: true, showCategories: true })
      return
    }
    if (results.length === 0) {
      say(NOT_FOUND_TEXT, { showCategories: true, chips: [{ kind: 'inquiry' }] })
      return
    }
    say('이런 질문을 찾았어요.', { chips: results.map((faq) => ({ kind: 'question', faq })) })
  }

  /** 저장된 대화를 되살린다(onMounted에서 호출). 읽기·형식 오류면 새 대화로 시작한다. */
  function restore(): void {
    try {
      const raw = window.sessionStorage.getItem(FAQ_CONVERSATION_STORAGE_KEY)
      if (raw === null) return
      const stored = JSON.parse(raw) as Partial<StoredConversation>
      if (!Array.isArray(stored.messages) || !stored.messages.every(isStoredMessage)) {
        throw new Error('저장된 대화 형식이 올바르지 않습니다.')
      }
      messages.value = stored.messages.slice(-FAQ_CONVERSATION_MAX_MESSAGES)
      open.value = stored.open === true
    } catch (error) {
      // 복원 실패는 새 대화로 시작하면 되므로 화면에 알리지 않는다. 깨진 값은 지워 다음에도 실패하지 않게 한다.
      console.warn('[faq] 저장된 대화 복원 실패 — 새 대화로 시작', error)
      messages.value = []
      open.value = false
      removeStored()
    }
  }

  function removeStored(): void {
    try {
      window.sessionStorage.removeItem(FAQ_CONVERSATION_STORAGE_KEY)
    } catch (error) {
      console.warn('[faq] 저장된 대화 삭제 실패', error)
    }
  }

  /** 대화·열림 상태가 바뀔 때마다 저장한다(onMounted 이후 호출 · 저장 실패는 대화에 영향 없음). */
  function persist(): void {
    watch([messages, open], () => {
      try {
        const stored: StoredConversation = { open: open.value, messages: messages.value }
        window.sessionStorage.setItem(FAQ_CONVERSATION_STORAGE_KEY, JSON.stringify(stored))
      } catch (error) {
        console.warn('[faq] 대화 저장 실패', error)
      }
    }, { deep: true })
  }

  return {
    messages,
    open,
    typing,
    suggestions: faqSuggest.suggestions,
    onInput: faqSuggest.onInput,
    clearSuggestions: faqSuggest.clear,
    openPanel,
    closePanel,
    selectChip,
    selectCategory,
    submitText,
    restore,
    persist,
  }
}
