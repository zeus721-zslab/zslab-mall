import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { createVuetify } from 'vuetify'
import { flushPromises } from '@vue/test-utils'
import AdminMemberDetailPage from '#layers/admin/app/pages/admin/members/[id].vue'

/**
 * 회원 상세 임시 비밀번호 발급(FE-55·D-204·외부 검토 R2 Q7 보강). 발급 성공 뒤 토스트 호출 인자 전체에 평문이 없고(성공 토스트 자체가 없음),
 * 결과 다이얼로그를 닫고 다른 값으로 재발급하면 이전 평문이 DOM 어디에도 남지 않는다. API·토스트·라우트는 mock.
 */
const { membersApiMock, toastMock } = vi.hoisted(() => ({
  membersApiMock: { get: vi.fn(), resetPassword: vi.fn(), listOrders: vi.fn(), listClaims: vi.fn(), update: vi.fn(), withdraw: vi.fn(), changeGrade: vi.fn() },
  toastMock: { success: vi.fn(), warning: vi.fn(), danger: vi.fn(), info: vi.fn(), show: vi.fn() },
}))
vi.mock('#layers/admin/app/composables/useAdminMembers', () => ({ useAdminMembers: () => membersApiMock }))
vi.mock('#layers/admin/app/composables/useAdminToast', () => ({ useAdminToast: () => toastMock }))
mockNuxtImport('definePageMeta', () => () => {})

const FIRST = 'Abcd2345efgh'
const SECOND = 'Zyxw9876vuts'
const DETAIL = {
  publicId: 'usr_A',
  name: '홍길동',
  email: 'hong@e2e.invalid',
  phone: '010-1234-5678',
  createdAt: '2026-09-01T00:00:00',
  passwordChangeRequired: false,
  grade: { code: 'SILVER', source: 'AUTO' },
  addresses: [],
}

function body() {
  return document.body
}

function query<T extends HTMLElement>(testId: string): T | null {
  return body().querySelector<T>(`[data-testid="${testId}"]`)
}

async function click(testId: string): Promise<void> {
  const target = query<HTMLElement>(testId)
  if (!target) throw new Error(`${testId} 없음`)
  target.click()
  await flushPromises()
}

async function issueAndShow(password: string): Promise<void> {
  membersApiMock.resetPassword.mockResolvedValueOnce({ temporaryPassword: password })
  await click('action-reset-password')
  await click('member-reset-dialog-ok')
  expect(query('member-reset-result-value')?.textContent).toBe(password)
}

// 실제 라우터를 쓴다(useRoute/useRouter mock은 Nuxt 초기화를 깨뜨린다) — route 옵션으로 params.id를 준다.
async function mountPage(): Promise<void> {
  await mountSuspended(AdminMemberDetailPage, { route: '/admin/members/usr_A', global: { plugins: [createVuetify()] }, attachTo: document.body })
  await flushPromises()
}

async function closeResult(): Promise<void> {
  await click('member-reset-result-close')
  await click('member-reset-result-close-ok')
}

describe('회원 상세 임시 비밀번호 발급 — 토스트 무평문·재발급 시 이전 평문 소거(FE-55)', () => {
  beforeEach(() => {
    Object.values(membersApiMock).forEach((fn) => fn.mockReset())
    Object.values(toastMock).forEach((fn) => fn.mockReset())
    membersApiMock.get.mockResolvedValue(DETAIL)
    membersApiMock.listOrders.mockResolvedValue({ items: [], totalCount: 0, page: 0, size: 20 })
    membersApiMock.listClaims.mockResolvedValue({ items: [], totalCount: 0, page: 0, size: 20 })
    document.body.innerHTML = ''
    vi.stubGlobal('visualViewport', { width: 1280, height: 800, scale: 1, offsetLeft: 0, offsetTop: 0, addEventListener: () => {}, removeEventListener: () => {} })
  })
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('발급 성공 → 결과 다이얼로그에 평문·모든 토스트 호출 인자에 평문 없음(성공 토스트 0) · 상세 재조회', async () => {
    await mountPage()
    await issueAndShow(FIRST)
    expect(membersApiMock.resetPassword).toHaveBeenCalledWith('usr_A')
    expect(membersApiMock.get).toHaveBeenCalledTimes(2)
    const allToastCalls = Object.values(toastMock).flatMap((fn) => fn.mock.calls)
    expect(allToastCalls).toHaveLength(0)
    expect(JSON.stringify(allToastCalls)).not.toContain(FIRST)
  })

  it('닫기 확인 → 평문 DOM 소거 → 다른 값으로 재발급 → 새 값만 표시·이전 평문 DOM 부재 → 닫으면 둘 다 부재', async () => {
    await mountPage()
    await issueAndShow(FIRST)
    await closeResult()
    expect(body().innerHTML).not.toContain(FIRST)

    await issueAndShow(SECOND)
    expect(body().innerHTML).not.toContain(FIRST)
    expect(query('member-reset-result-copy-notice')).toBeNull()
    await closeResult()
    expect(body().innerHTML).not.toContain(SECOND)
    expect(body().innerHTML).not.toContain(FIRST)
    expect(JSON.stringify(Object.values(toastMock).flatMap((fn) => fn.mock.calls))).not.toMatch(new RegExp(`${FIRST}|${SECOND}`))
  })
})
