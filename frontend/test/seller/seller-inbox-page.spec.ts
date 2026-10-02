import { describe, it, expect, beforeEach, vi } from 'vitest'
import { defineComponent, h, type PropType } from 'vue'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { createVuetify } from 'vuetify'
import { flushPromises } from '@vue/test-utils'
import SellerInboxPage from '#layers/seller/app/pages/seller/inbox.vue'
import type { InboxItemResponse, InboxResponse } from '~/types/inbox'
import type { InboxItem } from '~/lib/inbox-view'

/**
 * 셀러 인박스 페이지 — 처리 후 다음 선택과 변경 신호 재조회의 경합(warn P-04 · 관리자 인박스와 같은 구조). 목록 API·배지·변경 신호 구독은 mock,
 * 목록·상세는 stub(선택 항목만 본다). 실제 라우터를 쓴다(PC 폭 — 2단).
 */
const { inboxApiMock, streamState } = vi.hoisted(() => ({
  inboxApiMock: { list: vi.fn() },
  streamState: { reload: null as (() => Promise<void>) | null },
}))
vi.mock('#layers/seller/app/composables/useSellerInbox', () => ({ useSellerInbox: () => inboxApiMock }))
vi.mock('#layers/seller/app/composables/useSellerInboxBadge', () => ({ useSellerInboxBadge: () => ({ set: vi.fn() }) }))
vi.mock('#layers/seller/app/composables/useSellerInboxStream', () => ({
  useSellerInboxStream: (task: () => Promise<void>) => { streamState.reload = task },
}))
// 실제 라우터의 페이지 미들웨어(seller·seller-vuetify)가 로그인으로 돌리지 않게 셀러 세션을 확인된 상태로 둔다(Vuetify는 테스트 플러그인으로 설치).
vi.mock('#layers/seller/app/stores/sellerAuth', () => ({
  useSellerAuthStore: () => ({ ensureSession: async () => {}, isAuthenticated: true, role: 'SELLER', passwordChangeRequired: false, suspended: false, clearSession: () => {} }),
}))
vi.mock('#layers/seller/app/lib/vuetify', () => ({ ensureSellerVuetify: async () => {} }))
mockNuxtImport('definePageMeta', () => () => {})

function row(ref: string): InboxItemResponse {
  return { type: 'QUESTION_UNANSWERED', ref, title: ref, overdue: false, targetKey: 'PRODUCT_QUESTION' }
}

function response(items: InboxItemResponse[]): InboxResponse {
  return { items, counts: [], truncated: false }
}

function deferred(): { promise: Promise<InboxResponse>; resolve: (value: InboxResponse) => void } {
  let resolve: (value: InboxResponse) => void = () => {}
  const promise = new Promise<InboxResponse>((settle) => { resolve = settle })
  return { promise, resolve }
}

const detailState = { item: null as InboxItem | null, emitProcessed: null as (() => void) | null }
const DetailStub = defineComponent({
  props: { item: { type: Object as PropType<InboxItem | null>, default: null }, nowMs: { type: Number, default: 0 } },
  emits: ['processed'],
  setup(props, { emit }) {
    detailState.emitProcessed = () => emit('processed', 'done')
    return () => {
      detailState.item = props.item
      return h('div')
    }
  },
})
const EmptyStub = defineComponent({ setup: () => () => h('div') })

describe('셀러 인박스 페이지 — 처리 후 다음 선택 · 변경 신호 경합(warn P-04)', () => {
  beforeEach(() => {
    inboxApiMock.list.mockReset()
    streamState.reload = null
    detailState.item = null
    document.body.innerHTML = ''
  })

  it('처리 쪽 load 진행 중 변경 신호 load가 끼어들어도 처리한 항목 다음(C)을 고른다 — 첫 항목(A)으로 되돌아가지 않음', async () => {
    // 마운트 중 라우트 확정으로 목록을 여러 번 읽을 수 있어 기본 응답은 지속 mock으로 둔다.
    inboxApiMock.list.mockResolvedValue(response([row('pq_A'), row('pq_B'), row('pq_C')]))
    await mountSuspended(SellerInboxPage, {
      route: '/seller/inbox?selected=QUESTION_UNANSWERED:pq_B',
      global: { plugins: [createVuetify()], stubs: { SellerInboxDetail: DetailStub, SellerInboxList: EmptyStub, SellerPageHeader: EmptyStub } },
      attachTo: document.body,
    })
    await flushPromises()
    expect(detailState.item?.ref).toBe('pq_B') // 가운데 항목을 처리한다(다음 = C · 목록 맞추기 = 첫 항목 A로 갈린다)

    const processedLoad = deferred()
    const signalLoad = deferred()
    inboxApiMock.list.mockReturnValueOnce(processedLoad.promise).mockReturnValueOnce(signalLoad.promise)
    detailState.emitProcessed?.() // 처리 완료 → load(seq N)
    await flushPromises()
    const signal = streamState.reload?.() // 변경 신호 → load(seq N+1) · 처리 쪽 load는 버려진다
    signalLoad.resolve(response([row('pq_A'), row('pq_C')]))
    await signal
    processedLoad.resolve(response([row('pq_A'), row('pq_B'), row('pq_C')]))
    await flushPromises()

    // 선택은 router.replace(페이지 미들웨어 비동기 통과) 뒤 반영된다.
    await vi.waitFor(() => expect(detailState.item?.ref).toBe('pq_C'))
  })
})
