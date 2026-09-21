import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { createVuetify } from 'vuetify'
import { flushPromises } from '@vue/test-utils'
import AdminTemporaryPasswordDialog from '#layers/admin/app/components/admin/AdminTemporaryPasswordDialog.vue'

/**
 * 임시 비밀번호 1회 표시 다이얼로그(FE-55·D-204). 평문 monospace 표시·복사(성공·실패 인라인 안내)·닫기 2단(확인 → closed)·닫힌 뒤 값·안내 부재.
 * 평문은 다이얼로그 DOM과 clipboard 외 어디에도 나가지 않는다(console.warn 인자에도 없음).
 */
const PASSWORD = 'Abcd2345efgh'
const TEST_ID = 'tp-dialog'

function body() {
  return document.body
}

function element<T extends HTMLElement>(suffix: string): T | null {
  return body().querySelector<T>(`[data-testid="${TEST_ID}-${suffix}"]`)
}

async function click(suffix: string): Promise<void> {
  const target = element<HTMLElement>(suffix)
  if (!target) throw new Error(`${TEST_ID}-${suffix} 없음`)
  target.click()
  await flushPromises()
}

async function mountDialog(password: string | null = PASSWORD) {
  const wrapper = await mountSuspended(AdminTemporaryPasswordDialog, {
    props: { open: false, temporaryPassword: null, recipientLabel: '홍길동(hong@e2e.invalid) 회원의 임시 비밀번호입니다.', testId: TEST_ID },
    global: { plugins: [createVuetify()] },
    attachTo: document.body,
  })
  await wrapper.setProps({ open: true, temporaryPassword: password })
  await flushPromises()
  return wrapper
}

describe('AdminTemporaryPasswordDialog — 1회 표시(FE-55)', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    vi.stubGlobal('visualViewport', { width: 1280, height: 800, scale: 1, offsetLeft: 0, offsetTop: 0, addEventListener: () => {}, removeEventListener: () => {} })
  })
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('열림 → 수신자·평문(monospace code)·안내 2줄·닫기 버튼(확인 단계 없음)', async () => {
    await mountDialog()
    expect(element('recipient')?.textContent).toContain('홍길동')
    const value = element<HTMLElement>('value')
    expect(value?.tagName).toBe('CODE')
    expect(value?.textContent).toBe(PASSWORD)
    expect(element('notice')?.textContent).toContain('이 창을 닫으면 다시 볼 수 없습니다')
    expect(element('notice')?.textContent).toContain('첫 로그인 시 비밀번호 변경이 강제됩니다')
    expect(element('close')).not.toBeNull()
    expect(element('close-confirm')).toBeNull()
    expect(element('close-ok')).toBeNull()
  })

  it('복사 성공 → clipboard.writeText(평문)·인라인 성공 안내 / 실패 → 실패 안내·console.warn 인자에 평문 없음', async () => {
    await mountDialog()
    // createVuetify가 navigator.userAgent를 읽으므로 clipboard 스텁은 마운트 뒤에 건다.
    const writeText = vi.fn().mockResolvedValue(undefined)
    vi.stubGlobal('navigator', { ...navigator, userAgent: navigator.userAgent, clipboard: { writeText } })
    await click('copy')
    expect(writeText).toHaveBeenCalledWith(PASSWORD)
    expect(element('copy-notice')?.textContent).toBe('임시 비밀번호를 복사했습니다.')

    writeText.mockRejectedValueOnce(new Error('denied'))
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    await click('copy')
    expect(element('copy-notice')?.textContent).toContain('복사하지 못했습니다')
    expect(warn).toHaveBeenCalled()
    expect(JSON.stringify(warn.mock.calls)).not.toContain(PASSWORD)
    warn.mockRestore()
  })

  it('닫기 → 확인 단계(경고·계속 보기·닫기) → 계속 보기는 유지 → 닫기 확인 → closed 1회 · 부모가 닫으면 값·안내·확인 단계 소거', async () => {
    const wrapper = await mountDialog()
    await click('close')
    expect(element('close-confirm')?.textContent).toContain('다시 볼 수 없습니다')
    await click('close-cancel')
    expect(element('close-confirm')).toBeNull()
    expect(element('value')?.textContent).toBe(PASSWORD)
    expect(wrapper.emitted('closed')).toBeUndefined()

    await click('close')
    await click('close-ok')
    expect(wrapper.emitted('closed')).toHaveLength(1)

    // 부모 규약: closed에서 값을 null로 지우고 닫는다 → DOM에 평문 없음
    await wrapper.setProps({ open: false, temporaryPassword: null })
    await flushPromises()
    expect(body().innerHTML).not.toContain(PASSWORD)
    await wrapper.setProps({ open: true, temporaryPassword: 'Zyxw9876vuts' })
    await flushPromises()
    expect(element('copy-notice')).toBeNull()
    expect(element('close-confirm')).toBeNull()
  })
})
