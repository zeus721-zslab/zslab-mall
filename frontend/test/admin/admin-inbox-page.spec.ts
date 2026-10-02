import { describe, it, expect, beforeEach, vi } from 'vitest'
import { defineComponent, h, type PropType } from 'vue'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { createVuetify } from 'vuetify'
import { flushPromises } from '@vue/test-utils'
import AdminInboxPage from '#layers/admin/app/pages/admin/inbox.vue'
import AdminInboxDetail from '#layers/admin/app/components/admin/AdminInboxDetail.vue'
import type { InboxItemResponse, InboxResponse } from '~/types/inbox'
import type { InboxItem } from '~/lib/inbox-view'

/**
 * 관리자 인박스 페이지 — 처리 후 다음 선택과 변경 신호 재조회의 경합(warn P-04) · 일괄 독촉 후 열린 셀러 지연 패널 재조회(warn W14).
 * 목록 API·배지·변경 신호 구독은 mock, 목록·상세·일괄 컴포넌트는 stub(선택 항목·패널 nonce만 본다). 실제 라우터를 쓴다(PC 폭 — 2단).
 */
const { inboxApiMock, streamState, toastMock, sellersApiMock } = vi.hoisted(() => ({
  inboxApiMock: { list: vi.fn(), sellerDelay: vi.fn(), nudgeSellers: vi.fn() },
  streamState: { reload: null as (() => Promise<void>) | null },
  toastMock: { success: vi.fn(), warning: vi.fn(), danger: vi.fn(), info: vi.fn(), show: vi.fn() },
  sellersApiMock: { get: vi.fn() },
}))
vi.mock('#layers/admin/app/composables/useAdminInbox', () => ({ useAdminInbox: () => inboxApiMock }))
vi.mock('#layers/admin/app/composables/useAdminInboxBadge', () => ({ useAdminInboxBadge: () => ({ set: vi.fn() }) }))
vi.mock('#layers/admin/app/composables/useAdminInboxStream', () => ({
  useAdminInboxStream: (task: () => Promise<void>) => { streamState.reload = task },
}))
vi.mock('#layers/admin/app/composables/useAdminToast', () => ({ useAdminToast: () => toastMock }))
vi.mock('#layers/admin/app/composables/useAdminSellers', () => ({ useAdminSellers: () => sellersApiMock }))
// 실제 라우터의 페이지 미들웨어(admin·vuetify)가 로그인으로 돌리지 않게 관리자 세션을 확인된 상태로 둔다(Vuetify는 테스트 플러그인으로 설치).
vi.mock('#layers/admin/app/stores/adminAuth', () => ({
  useAdminAuthStore: () => ({ ensureSession: async () => {}, isAuthenticated: true, role: 'ADMIN', clearSession: () => {} }),
}))
vi.mock('#layers/admin/app/lib/vuetify', () => ({ ensureVuetify: async () => {} }))
mockNuxtImport('definePageMeta', () => () => {})

function row(ref: string, type: InboxItemResponse['type'] = 'INQUIRY_UNANSWERED'): InboxItemResponse {
  return { type, ref, title: ref, subtitle: 'DELIVERY', overdue: false, targetKey: 'INQUIRY' }
}

function response(items: InboxItemResponse[]): InboxResponse {
  return { items, counts: [], truncated: false }
}

function deferred(): { promise: Promise<InboxResponse>; resolve: (value: InboxResponse) => void } {
  let resolve: (value: InboxResponse) => void = () => {}
  const promise = new Promise<InboxResponse>((settle) => { resolve = settle })
  return { promise, resolve }
}

const detailState = { item: null as InboxItem | null, panelNonce: -1, emitProcessed: null as (() => void) | null }
const DetailStub = defineComponent({
  props: { item: { type: Object as PropType<InboxItem | null>, default: null }, nowMs: { type: Number, default: 0 }, panelNonce: { type: Number, default: -1 } },
  emits: ['processed'],
  setup(props, { emit }) {
    detailState.emitProcessed = () => emit('processed', 'done')
    return () => {
      detailState.item = props.item
      detailState.panelNonce = props.panelNonce
      return h('div')
    }
  },
})
const bulkState = { emitDone: null as (() => void) | null }
const NudgeBulkStub = defineComponent({
  emits: ['done', 'clear'],
  setup(_props, { emit }) {
    bulkState.emitDone = () => emit('done')
    return () => h('div')
  },
})
const EmptyStub = defineComponent({ setup: () => () => h('div') })

async function mountPage(route = '/admin/inbox'): Promise<void> {
  await mountSuspended(AdminInboxPage, {
    route,
    global: {
      plugins: [createVuetify()],
      stubs: { AdminInboxDetail: DetailStub, AdminInboxSellerNudgeBulk: NudgeBulkStub, AdminInboxClaimBulkApprove: EmptyStub, AdminInboxList: EmptyStub, AdminPageHeader: EmptyStub },
    },
    attachTo: document.body,
  })
  await flushPromises()
}

function selectedRef(): string | null {
  return detailState.item?.ref ?? null
}

describe('관리자 인박스 페이지 — 처리 후 다음 선택 · 변경 신호 경합(warn P-04)', () => {
  beforeEach(() => {
    Object.values(inboxApiMock).forEach((fn) => fn.mockReset())
    streamState.reload = null
    detailState.item = null
    detailState.panelNonce = -1
    document.body.innerHTML = ''
  })

  it('처리 쪽 load 진행 중 변경 신호 load가 끼어들어도 처리한 항목 다음(C)을 고른다 — 첫 항목(A)으로 되돌아가지 않음', async () => {
    // 마운트 중 라우트 확정으로 목록을 여러 번 읽을 수 있어 기본 응답은 지속 mock으로 둔다.
    inboxApiMock.list.mockResolvedValue(response([row('inq_A'), row('inq_B'), row('inq_C')]))
    await mountPage('/admin/inbox?selected=INQUIRY_UNANSWERED:inq_B')
    expect(selectedRef()).toBe('inq_B') // 가운데 항목을 처리한다(다음 = C · 목록 맞추기 = 첫 항목 A로 갈린다)

    const processedLoad = deferred()
    const signalLoad = deferred()
    inboxApiMock.list.mockReturnValueOnce(processedLoad.promise).mockReturnValueOnce(signalLoad.promise)
    detailState.emitProcessed?.() // 처리 완료 → load(seq N)
    await flushPromises()
    const signal = streamState.reload?.() // 변경 신호 → load(seq N+1) · 처리 쪽 load는 버려진다
    signalLoad.resolve(response([row('inq_A'), row('inq_C')]))
    await signal
    processedLoad.resolve(response([row('inq_A'), row('inq_B'), row('inq_C')]))
    await flushPromises()

    // 선택은 router.replace(페이지 미들웨어 비동기 통과) 뒤 반영된다.
    await vi.waitFor(() => expect(selectedRef()).toBe('inq_C'))
  })
})

describe('관리자 인박스 페이지 — 일괄 독촉 후 셀러 지연 패널 재조회(warn W14)', () => {
  beforeEach(() => {
    Object.values(inboxApiMock).forEach((fn) => fn.mockReset())
    document.body.innerHTML = ''
  })

  it('일괄 독촉 done → 목록 재조회 뒤 패널 nonce가 바뀐다(같은 선택 키여도 패널을 새로 만든다)', async () => {
    inboxApiMock.list.mockResolvedValue(response([row('slr_A', 'SELLER_DELAY')]))
    await mountPage()
    await vi.waitFor(() => expect(selectedRef()).toBe('slr_A')) // PC는 첫 항목 자동 선택(router.replace 뒤 반영)
    expect(detailState.panelNonce).toBe(0)
    const callsBefore = inboxApiMock.list.mock.calls.length
    bulkState.emitDone?.()
    await flushPromises()
    expect(inboxApiMock.list).toHaveBeenCalledTimes(callsBefore + 1)
    expect(selectedRef()).toBe('slr_A')
    expect(detailState.panelNonce).toBe(1)
  })

  it('상세: panelNonce가 바뀌면 같은 항목의 셀러 지연 패널이 지연 정보를 다시 읽는다', async () => {
    const delay = { sellerPublicId: 'slr_A', companyName: '지연상회', deliveryReadyOverdueCount: 2, questionUnansweredOverdueCount: 0 }
    inboxApiMock.sellerDelay.mockResolvedValue(delay)
    const item: InboxItem = {
      key: 'SELLER_DELAY:slr_A', type: 'SELLER_DELAY', ref: 'slr_A', sourceRef: 'slr_A', step: null, title: '지연상회', subtitle: null,
      baseAt: null, dueAt: null, overdue: true, targetKey: 'SELLER', claimType: null, suggestion: null,
    }
    const wrapper = await mountSuspended(AdminInboxDetail, { props: { item, nowMs: 0, panelNonce: 0 }, global: { plugins: [createVuetify()] }, attachTo: document.body })
    await flushPromises()
    expect(inboxApiMock.sellerDelay).toHaveBeenCalledTimes(1)
    await wrapper.setProps({ panelNonce: 1 })
    await flushPromises()
    expect(inboxApiMock.sellerDelay).toHaveBeenCalledTimes(2)
  })
})
