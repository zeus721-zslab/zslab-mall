import { describe, it, expect, afterEach, beforeEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises, type VueWrapper } from '@vue/test-utils'
import RenewRatingStars from '~/skins/renew/components/RenewRatingStars.vue'
import RenewRatingInput from '~/skins/renew/components/RenewRatingInput.vue'
import RenewRatingBar from '~/skins/renew/components/RenewRatingBar.vue'
import RenewPhotoStrip from '~/skins/renew/components/RenewPhotoStrip.vue'
import RenewLightbox from '~/skins/renew/components/RenewLightbox.vue'
import RenewPhotoInput from '~/skins/renew/components/RenewPhotoInput.vue'
import RenewMoreButton from '~/skins/renew/components/RenewMoreButton.vue'
import type { ReviewFormPhoto, ReviewPhotoUploadResponse } from '~/types/review'

/**
 * Track 106-1 PR2 리뷰 공통 컴포넌트(renew): 별점 표시·입력 · 분포 막대 · 사진 띠 · 라이트박스 · 사진 입력 · 더보기.
 * 핵심 동작과 접근성 속성(라벨·역할·aria-pressed·aria-busy)을 확인한다. 라이트박스는 reka Portal이라 document 기준으로 조회한다.
 */

let mounted: VueWrapper | null = null
afterEach(() => {
  mounted?.unmount()
  mounted = null
})

describe('RenewRatingStars', () => {
  it('소수 평균 → 이미지 라벨 "5점 만점에 4.3점" · 별 4개 100% + 다섯째 30%', async () => {
    const wrapper = await mountSuspended(RenewRatingStars, { props: { value: 4.3 } })
    const root = wrapper.get('[data-testid="rating-stars"]')
    expect(root.attributes('role')).toBe('img')
    expect(root.attributes('aria-label')).toBe('별점 5점 만점에 4.3점')
    const widths = wrapper.findAll('span[style]').map((span) => span.attributes('style'))
    expect(widths).toEqual(['width: 100%;', 'width: 100%;', 'width: 100%;', 'width: 100%;', 'width: 30%;'])
  })

  it('정수 · 범위 밖 값은 0~5로 자른다', async () => {
    const wrapper = await mountSuspended(RenewRatingStars, { props: { value: 7 } })
    expect(wrapper.get('[data-testid="rating-stars"]').attributes('aria-label')).toBe('별점 5점 만점에 5점')
  })
})

describe('RenewRatingInput', () => {
  it('radiogroup + radio 5개(점수·문구 라벨) · 선택값 checked · 변경 → update:modelValue', async () => {
    const wrapper = await mountSuspended(RenewRatingInput, { props: { modelValue: 3, name: 'rating', label: '별점' } })
    const group = wrapper.get('[role="radiogroup"]')
    expect(group.attributes('aria-label')).toBe('별점')
    const radios = wrapper.findAll('input[type="radio"]')
    expect(radios).toHaveLength(5)
    expect(radios.map((radio) => radio.attributes('aria-label'))).toEqual([
      '1점 별로예요',
      '2점 그저 그래요',
      '3점 괜찮아요',
      '4점 좋아요',
      '5점 최고예요',
    ])
    expect((radios[2]!.element as HTMLInputElement).checked).toBe(true)
    await radios[4]!.setValue(true)
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual([5])
  })

  it('disabled → radio 잠김', async () => {
    const wrapper = await mountSuspended(RenewRatingInput, { props: { modelValue: null, name: 'rating', label: '별점', disabled: true } })
    expect(wrapper.findAll('input[type="radio"]').every((radio) => (radio.element as HTMLInputElement).disabled)).toBe(true)
  })
})

describe('RenewRatingBar', () => {
  it('비율 → 막대 폭 % · 값 문구 · 막대는 읽지 않음', async () => {
    const wrapper = await mountSuspended(RenewRatingBar, { props: { label: '5점', ratio: 0.625, valueText: '63%', strong: true } })
    expect(wrapper.get('[data-testid="rating-bar-fill"]').attributes('style')).toBe('width: 63%;')
    expect(wrapper.text()).toContain('5점')
    expect(wrapper.text()).toContain('63%')
    expect(wrapper.find('[aria-hidden="true"]').exists()).toBe(true)
  })

  it('stacked(키워드 집계) → 이름 전체를 잘림 없이 한 줄에(truncate 없음 · 두 칸 차지) · 기본 배치는 이름 칸 truncate', async () => {
    const LABEL = '상품 · 사이즈가 딱 맞아요'
    const stacked = await mountSuspended(RenewRatingBar, { props: { label: LABEL, ratio: 0.75, valueText: '75%', stacked: true } })
    const stackedLabel = stacked.get('[data-testid="rating-bar-label"]')
    expect(stackedLabel.text()).toBe(LABEL)
    expect(stackedLabel.classes()).not.toContain('truncate')
    expect(stackedLabel.classes()).toContain('col-span-2')

    const inline = await mountSuspended(RenewRatingBar, { props: { label: '5점', ratio: 0.5, valueText: '50%' } })
    expect(inline.get('[data-testid="rating-bar-label"]').classes()).toContain('truncate')
  })
})

describe('RenewPhotoStrip', () => {
  const PHOTOS = Array.from({ length: 10 }, (_, index) => `/p/${index}.png`)

  it('max보다 많으면 max칸 · 마지막 칸 "+N"(가리는 수) · 누르면 open(순번)', async () => {
    const wrapper = await mountSuspended(RenewPhotoStrip, { props: { photos: PHOTOS, max: 4 } })
    const items = wrapper.findAll('[data-testid="photo-strip-item"]')
    expect(items).toHaveLength(4)
    expect(wrapper.get('[data-testid="photo-strip-more"]').text()).toBe('+7')
    expect(items[3]!.attributes('aria-label')).toBe('리뷰 사진 7장 더 보기')
    await items[1]!.trigger('click')
    expect(wrapper.emitted('open')?.[0]).toEqual([1])
  })

  it('max 이하 → "+N" 없음', async () => {
    const wrapper = await mountSuspended(RenewPhotoStrip, { props: { photos: PHOTOS.slice(0, 3), max: 4 } })
    expect(wrapper.find('[data-testid="photo-strip-more"]').exists()).toBe(false)
  })
})

describe('RenewLightbox', () => {
  const PHOTOS = ['/p/a.png', '/p/b.png', '/p/c.png']

  function query<T extends Element>(testId: string): T | null {
    return document.querySelector<T>(`[data-testid="${testId}"]`)
  }

  it('시작 순번으로 열림 · 다음/이전 버튼 · → 키 · 양끝 버튼 잠금 · 제목·카운터', async () => {
    mounted = await mountSuspended(RenewLightbox, { props: { open: true, photos: PHOTOS, startIndex: 1 }, attachTo: document.body })
    await flushPromises()
    expect(query('lightbox')?.getAttribute('role')).toBe('dialog')
    expect(query<HTMLImageElement>('lightbox-image')?.getAttribute('src')).toBe('/p/b.png')
    expect(query('lightbox-counter')?.textContent).toBe('2 / 3')

    query<HTMLButtonElement>('lightbox-next')!.click()
    await flushPromises()
    expect(query<HTMLImageElement>('lightbox-image')?.getAttribute('src')).toBe('/p/c.png')
    expect(query<HTMLButtonElement>('lightbox-next')?.disabled).toBe(true)

    query('lightbox')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft', bubbles: true }))
    await flushPromises()
    expect(query('lightbox-counter')?.textContent).toBe('2 / 3')
    query<HTMLButtonElement>('lightbox-prev')!.click()
    await flushPromises()
    expect(query<HTMLButtonElement>('lightbox-prev')?.disabled).toBe(true)
  })

  it('Esc → update:open(false)', async () => {
    mounted = await mountSuspended(RenewLightbox, { props: { open: true, photos: PHOTOS }, attachTo: document.body })
    await flushPromises()
    document.activeElement?.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await flushPromises()
    expect(mounted.emitted('update:open')?.[0]).toEqual([false])
  })
})

describe('RenewPhotoInput', () => {
  let objectUrlSequence = 0
  beforeEach(() => {
    objectUrlSequence = 0
    vi.stubGlobal('URL', Object.assign(URL, {
      createObjectURL: vi.fn(() => `blob:preview-${objectUrlSequence++}`),
      revokeObjectURL: vi.fn(),
    }))
  })

  function png(name: string): File {
    return new File([new Uint8Array([137, 80, 78, 71])], name, { type: 'image/png' })
  }

  function success(index: number): ReviewPhotoUploadResponse {
    return { results: [{ success: true, attachmentId: `att_${index}`, url: `/u/${index}`, thumbnailUrl: `/u/${index}_thumb` }], successCount: 1, failureCount: 0 }
  }

  async function selectFiles(wrapper: VueWrapper, files: File[]): Promise<void> {
    const input = wrapper.get('[data-testid="photo-input-file"]').element as HTMLInputElement
    Object.defineProperty(input, 'files', { value: files, configurable: true })
    await wrapper.get('[data-testid="photo-input-file"]').trigger('change')
  }

  it('여러 장을 골라도 한 장씩 차례로 올린다(앞 요청이 끝나야 다음) · 미리보기 = object URL · 성공 순서대로 v-model', async () => {
    const resolvers: ((response: ReviewPhotoUploadResponse) => void)[] = []
    const upload = vi.fn(() => new Promise<ReviewPhotoUploadResponse>((resolve) => resolvers.push(resolve)))
    const photos: ReviewFormPhoto[] = []
    const wrapper = await mountSuspended(RenewPhotoInput, {
      props: { modelValue: photos, upload, max: 5, 'onUpdate:modelValue': (next: ReviewFormPhoto[]) => wrapper.setProps({ modelValue: next }) },
    })
    await selectFiles(wrapper, [png('a.png'), png('b.png')])
    await flushPromises()
    expect(upload).toHaveBeenCalledTimes(1)
    expect(wrapper.findAll('[data-testid="photo-input-pending"] img').map((image) => image.attributes('src'))).toEqual(['blob:preview-0', 'blob:preview-1'])

    resolvers[0]!(success(0))
    await flushPromises()
    expect(upload).toHaveBeenCalledTimes(2)
    resolvers[1]!(success(1))
    await flushPromises()

    const emitted = wrapper.emitted<[ReviewFormPhoto[]]>('update:modelValue')!
    expect(emitted.at(-1)![0].map((photo) => [photo.attachmentId, photo.previewUrl])).toEqual([
      ['att_0', 'blob:preview-0'],
      ['att_1', 'blob:preview-1'],
    ])
  })

  it('파일별 실패 → 칸에 실패 상태·문구 · 다시 시도로 재업로드 · 빼기로 제거(object URL 해제)', async () => {
    const upload = vi.fn()
      .mockResolvedValueOnce({ results: [{ success: false, fileName: 'a.png', code: 'INVALID_IMAGE' }], successCount: 0, failureCount: 1 })
      .mockRejectedValueOnce({ statusCode: 413 })
    const wrapper = await mountSuspended(RenewPhotoInput, { props: { modelValue: [], upload, max: 5 } })
    await selectFiles(wrapper, [png('a.png')])
    await flushPromises()
    expect(wrapper.get('[data-testid="photo-input-pending"]').attributes('data-state')).toBe('failed')
    expect(wrapper.get('[data-testid="photo-input-errors"]').text()).toContain('이미지를 읽을 수 없습니다.')
    // 실패 칸 수를 부모에 알린다(부모가 등록을 막는다)
    expect(wrapper.emitted<[number]>('update:failed')?.at(-1)).toEqual([1])

    await wrapper.get('[data-testid="photo-input-retry"]').trigger('click')
    await flushPromises()
    expect(upload).toHaveBeenCalledTimes(2)
    expect(wrapper.get('[data-testid="photo-input-errors"]').text()).toContain('파일이 너무 큽니다')

    await wrapper.get('[data-testid="photo-input-drop"]').trigger('click')
    expect(wrapper.find('[data-testid="photo-input-pending"]').exists()).toBe(false)
    expect(wrapper.emitted<[number]>('update:failed')?.at(-1)).toEqual([0])
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:preview-0')
  })

  it('최대 장수 넘는 파일·형식 불일치는 올리지 않고 사유를 보인다 · 가득 차면 추가 칸 없음', async () => {
    const upload = vi.fn()
    const existing = Array.from({ length: 4 }, (_, index) => ({ attachmentId: `att_${index}`, previewUrl: `/t/${index}`, name: `기존 ${index}` }))
    const wrapper = await mountSuspended(RenewPhotoInput, { props: { modelValue: existing, upload, max: 5 } })
    await selectFiles(wrapper, [new File(['x'], 'doc.gif', { type: 'image/gif' }), png('a.png'), png('b.png')])
    await flushPromises()
    expect(upload).toHaveBeenCalledTimes(1)
    const errors = wrapper.get('[data-testid="photo-input-errors"]').text()
    expect(errors).toContain('doc.gif')
    expect(errors).toContain('b.png: 사진은 최대 5장까지')
    expect(wrapper.find('[data-testid="photo-input-add"]').exists()).toBe(false)
  })
})

describe('RenewMoreButton', () => {
  it('불러오는 중 → 잠김·aria-busy · 아니면 more', async () => {
    const loading = await mountSuspended(RenewMoreButton, { props: { loading: true } })
    const busy = loading.get('[data-testid="more-button"]')
    expect((busy.element as HTMLButtonElement).disabled).toBe(true)
    expect(busy.attributes('aria-busy')).toBe('true')

    const idle = await mountSuspended(RenewMoreButton, { props: { loading: false, label: '리뷰 더보기' } })
    expect(idle.text()).toBe('리뷰 더보기')
    await idle.get('[data-testid="more-button"]').trigger('click')
    expect(idle.emitted('more')).toHaveLength(1)
  })
})
