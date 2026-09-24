import { describe, it, expect, afterEach } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises, type VueWrapper } from '@vue/test-utils'
import DialogConfirm from '~/components/ui/dialog/DialogConfirm.vue'

/**
 * FE-79 DialogConfirm pending: 확인 버튼 잠금 + 처리 중 표시(스피너·aria-busy) · Esc·닫기 요청 무시.
 * 모달은 reka Portal(document.body)이라 wrapper가 아닌 document 기준으로 조회한다.
 */
const BASE_PROPS = { open: true, title: '이 배송지를 삭제하시겠습니까?', description: '홍길동 · 서울 강남구 테헤란로 152', confirmLabel: '삭제하기', tone: 'danger' as const }

let mounted: VueWrapper | null = null
afterEach(() => {
  mounted?.unmount()
  mounted = null
})

function confirmButton(): HTMLButtonElement {
  return document.querySelector<HTMLButtonElement>('[data-testid="dialog-confirm-action"]')!
}

async function pressEscape(): Promise<void> {
  document.activeElement?.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
  document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
  await flushPromises()
}

describe('DialogConfirm — pending', () => {
  it('pending 아님 → 확인 활성·스피너 없음 · Esc → update:open(false)', async () => {
    mounted = await mountSuspended(DialogConfirm, { props: { ...BASE_PROPS, pending: false }, attachTo: document.body })
    await flushPromises()
    expect(confirmButton().disabled).toBe(false)
    expect(confirmButton().getAttribute('aria-busy')).toBe('false')
    expect(confirmButton().querySelector('[data-slot="confirm-spinner"]')).toBeNull()
    await pressEscape()
    expect(mounted.emitted('update:open')?.[0]).toEqual([false])
  })

  it('pending → 확인·취소 잠금 · 스피너·aria-busy · Esc 무시(update:open 없음) · 확인 클릭 무반응', async () => {
    mounted = await mountSuspended(DialogConfirm, { props: { ...BASE_PROPS, confirmLabel: '삭제 중…', pending: true }, attachTo: document.body })
    await flushPromises()
    const button = confirmButton()
    expect(button.disabled).toBe(true)
    expect(button.getAttribute('aria-busy')).toBe('true')
    expect(button.querySelector('[data-slot="confirm-spinner"]')).not.toBeNull()
    expect(button.textContent?.trim()).toBe('삭제 중…')
    const cancel = [...document.querySelectorAll<HTMLButtonElement>('button')].find((element) => element.textContent?.trim() === '취소')
    expect(cancel?.disabled).toBe(true)
    await pressEscape()
    expect(mounted.emitted('update:open')).toBeUndefined()
    button.click()
    expect(mounted.emitted('confirm')).toBeUndefined()
  })
})

// FE-79 tone·notice: 강조는 색을 늘리지 않고 구조로(결과 안내 + 동작명 버튼). tone 기본 primary · danger는 삭제·취소·철회만.
describe('DialogConfirm — tone·notice 슬롯', () => {
  it('tone 기본 → btn-primary · danger → btn-danger', async () => {
    const { tone: _tone, ...withoutTone } = BASE_PROPS
    mounted = await mountSuspended(DialogConfirm, { props: withoutTone, attachTo: document.body })
    await flushPromises()
    expect(confirmButton().classList).toContain('btn-primary')
    expect(confirmButton().classList).not.toContain('btn-danger')
    mounted.unmount()

    mounted = await mountSuspended(DialogConfirm, { props: BASE_PROPS, attachTo: document.body })
    await flushPromises()
    expect(confirmButton().classList).toContain('btn-danger')
    expect(confirmButton().classList).not.toContain('btn-primary')
  })

  it('notice 슬롯 없음 → 안내 없음 / 있음 → RenewNotice warning(testid·줄바꿈 문구 그대로)', async () => {
    mounted = await mountSuspended(DialogConfirm, { props: BASE_PROPS, attachTo: document.body })
    await flushPromises()
    expect(document.querySelector('[data-slot="confirm-notice"]')).toBeNull()
    mounted.unmount()

    mounted = await mountSuspended(DialogConfirm, {
      props: { ...BASE_PROPS, noticeTestId: 'remove-notice' },
      slots: { notice: () => '삭제하면 되돌릴 수 없어요.\n두 번째 줄' },
      attachTo: document.body,
    })
    await flushPromises()
    const notice = document.querySelector<HTMLElement>('[data-slot="confirm-notice"]')!
    expect(notice.getAttribute('data-tone')).toBe('warning')
    expect(notice.getAttribute('data-testid')).toBe('remove-notice')
    expect(notice.textContent?.trim()).toBe('삭제하면 되돌릴 수 없어요.\n두 번째 줄')
    expect(notice.querySelector('p')?.classList).toContain('whitespace-pre-line')
  })
})
