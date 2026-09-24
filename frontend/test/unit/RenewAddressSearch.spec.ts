import { describe, it, expect, afterEach, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import RenewAddressSearch from '~/skins/renew/components/RenewAddressSearch.vue'
import { loadPostcode, toAddressFields, type PostcodeData, type PostcodeOptions } from '~/skins/renew/postcode-loader'

/**
 * FE-78 A-2 주소 검색(모달 + 요약 카드). 카카오 스크립트는 부르지 않는다 — 성공 경로는 window.kakao.Postcode 모의, 실패 경로는
 * script 삽입을 가로채 error·타임아웃을 직접 일으킨다. 모달은 body로 포털되므로 document에서 찾는다.
 */
const DETAIL_INPUT_ID = 'address-search-detail'
const EMPTY_ADDRESS = { zonecode: '', addressRoad: '', addressJibun: '' }
const SELECTED_ADDRESS = { zonecode: '06236', addressRoad: '서울 강남구 테헤란로 152 (역삼동)', addressJibun: '서울 강남구 역삼동 737' }

function postcodeData(overrides: Partial<PostcodeData> = {}): PostcodeData {
  return {
    zonecode: '06236',
    roadAddress: '서울 강남구 테헤란로 152',
    autoRoadAddress: '',
    jibunAddress: '서울 강남구 역삼동 737',
    autoJibunAddress: '',
    bname: '역삼동',
    buildingName: '강남파이낸스센터',
    apartment: 'N',
    ...overrides,
  }
}

interface FakePostcodeCall {
  options: PostcodeOptions
  element: HTMLElement | null
}

/** window.kakao(또는 daum).Postcode 모의. 생성 옵션과 embed 대상을 기록한다. */
function installFakePostcode(namespace: 'kakao' | 'daum'): FakePostcodeCall[] {
  const calls: FakePostcodeCall[] = []
  class FakePostcode {
    private readonly call: FakePostcodeCall
    constructor(options: PostcodeOptions) {
      this.call = { options, element: null }
      calls.push(this.call)
    }
    embed(element: HTMLElement): void {
      this.call.element = element
    }
  }
  window[namespace] = { Postcode: FakePostcode }
  return calls
}

/** script 삽입을 가로채 실제 요청 없이 넣으려던 script를 돌려준다. */
function interceptScriptInsert(): { scripts: HTMLScriptElement[] } {
  const scripts: HTMLScriptElement[] = []
  vi.spyOn(document.head, 'appendChild').mockImplementation(<T extends Node>(node: T): T => {
    if (node instanceof HTMLScriptElement) scripts.push(node)
    return node
  })
  return { scripts }
}

const mounted: Array<{ unmount: () => void }> = []

async function mountSearch(address: typeof EMPTY_ADDRESS = EMPTY_ADDRESS) {
  const wrapper = await mountSuspended(RenewAddressSearch, { props: { ...address, detailInputId: DETAIL_INPUT_ID } })
  mounted.push(wrapper)
  return wrapper
}

function dialog(): HTMLElement | null {
  return document.querySelector<HTMLElement>('[data-testid="address-search-dialog"]')
}

function dialogButton(text: string): HTMLButtonElement {
  const button = Array.from(document.querySelectorAll<HTMLButtonElement>('[data-testid="address-search-dialog"] button')).find(
    (candidate) => candidate.textContent?.trim() === text,
  )
  if (!button) throw new Error(`모달 버튼 없음: ${text}`)
  return button
}

// reka FocusScope는 닫힘 자동 포커스를 다음 틱(setTimeout 0)에 처리한다.
async function settle(): Promise<void> {
  await flushPromises()
  await new Promise((resolve) => setTimeout(resolve, 0))
  await flushPromises()
}

afterEach(() => {
  mounted.splice(0).forEach((wrapper) => wrapper.unmount())
  delete window.kakao
  delete window.daum
  document.getElementById(DETAIL_INPUT_ID)?.remove()
  vi.restoreAllMocks()
  vi.useRealTimers()
})

describe('toAddressFields — 값 연결·참고항목 조합·도로명 대체', () => {
  it('법정동(동·로·가로 끝남)만 참고항목에 넣고, 건물명은 공동주택일 때만 넣는다', () => {
    expect(toAddressFields(postcodeData())?.addressRoad).toBe('서울 강남구 테헤란로 152 (역삼동)')
    expect(toAddressFields(postcodeData({ apartment: 'Y', buildingName: '래미안' }))?.addressRoad).toBe('서울 강남구 테헤란로 152 (역삼동, 래미안)')
    expect(toAddressFields(postcodeData({ bname: '봉담리', apartment: 'Y', buildingName: '래미안' }))?.addressRoad).toBe('서울 강남구 테헤란로 152 (래미안)')
    expect(toAddressFields(postcodeData({ bname: '', buildingName: '' }))?.addressRoad).toBe('서울 강남구 테헤란로 152')
  })

  it('지번은 jibunAddress, 비어 있으면 autoJibunAddress를 쓴다', () => {
    expect(toAddressFields(postcodeData())).toEqual(SELECTED_ADDRESS)
    expect(toAddressFields(postcodeData({ jibunAddress: '', autoJibunAddress: '서울 강남구 역삼동 737-1' }))?.addressJibun).toBe('서울 강남구 역삼동 737-1')
  })

  it('roadAddress가 비면 autoRoadAddress로 채우고, 둘 다 비면 null(선택 거부)', () => {
    expect(toAddressFields(postcodeData({ roadAddress: '', autoRoadAddress: '서울 강남구 테헤란로 150' }))?.addressRoad).toBe('서울 강남구 테헤란로 150 (역삼동)')
    expect(toAddressFields(postcodeData({ roadAddress: '', autoRoadAddress: '' }))).toBeNull()
  })
})

describe('RenewAddressSearch — 모달 + 요약 카드', () => {
  it('선택 전 = "주소 검색" 버튼 · 선택 후 = 요약 카드(우편번호·도로명·지번 + 변경)', async () => {
    const wrapper = await mountSearch()
    expect(wrapper.find('[data-testid="address-summary"]').exists()).toBe(false)
    expect(wrapper.find('button').text()).toBe('주소 검색')

    await wrapper.setProps(SELECTED_ADDRESS)
    const summary = wrapper.find('[data-testid="address-summary"]')
    expect(summary.exists()).toBe(true)
    expect(summary.text()).toContain('06236')
    expect(summary.text()).toContain('서울 강남구 테헤란로 152 (역삼동)')
    expect(summary.text()).toContain('서울 강남구 역삼동 737')
    expect(summary.find('button').text()).toBe('변경')
    expect(wrapper.findAll('button').some((button) => button.text() === '주소 검색')).toBe(false)
  })

  it('주소 검색 → 모달 안 임베드(focusInput) · 고르면 세 칸 값 전달 · 모달 닫힘 · 상세 주소로 포커스', async () => {
    const calls = installFakePostcode('kakao')
    const detailInput = document.createElement('input')
    detailInput.id = DETAIL_INPUT_ID
    document.body.appendChild(detailInput)

    const wrapper = await mountSearch()
    await wrapper.find('button').trigger('click')
    await settle()

    expect(dialog()).not.toBeNull()
    expect(calls).toHaveLength(1)
    const call = calls[0]!
    expect(dialog()!.contains(call.element)).toBe(true)
    expect(call.options.focusInput).toBe(true)
    expect(call.options.theme.outlineColor).toBe('#E4DEF1')

    call.options.oncomplete(postcodeData())
    await settle()

    expect(wrapper.emitted('update:zonecode')?.at(-1)).toEqual(['06236'])
    expect(wrapper.emitted('update:addressRoad')?.at(-1)).toEqual(['서울 강남구 테헤란로 152 (역삼동)'])
    expect(wrapper.emitted('update:addressJibun')?.at(-1)).toEqual(['서울 강남구 역삼동 737'])
    expect(dialog()).toBeNull()
    expect(document.activeElement).toBe(detailInput)
  })

  it('nested(폼 모달 안 · FE-79) → 검색 모달 오버레이 투명 · 기본(주문서) → 기존 어두운 오버레이', async () => {
    installFakePostcode('kakao')
    const overlayClasses = async (nested: boolean): Promise<string[]> => {
      const wrapper = await mountSuspended(RenewAddressSearch, { props: { ...EMPTY_ADDRESS, detailInputId: DETAIL_INPUT_ID, nested } })
      await wrapper.find('button').trigger('click')
      await settle()
      const overlay = document.querySelector<HTMLElement>('[data-slot="dialog-overlay"]')
      const classes = overlay ? Array.from(overlay.classList) : []
      wrapper.unmount()
      await settle()
      return classes
    }
    const standalone = await overlayClasses(false)
    expect(standalone).toContain('bg-foreground/40')
    expect(standalone).not.toContain('bg-transparent')
    const nested = await overlayClasses(true)
    expect(nested).toContain('bg-transparent')
    expect(nested).not.toContain('bg-foreground/40')
  })

  it('변경 → 같은 모달로 다시 검색한다', async () => {
    const calls = installFakePostcode('kakao')
    const wrapper = await mountSearch(SELECTED_ADDRESS)
    await wrapper.find('[data-testid="address-summary"] button').trigger('click')
    await settle()

    expect(dialog()).not.toBeNull()
    expect(calls).toHaveLength(1)
    expect(dialog()!.contains(calls[0]!.element)).toBe(true)
  })

  it('도로명·자동 도로명이 모두 빈 결과 → 선택 거부(값 미전달) · 모달 유지 · 안내', async () => {
    const calls = installFakePostcode('kakao')
    const wrapper = await mountSearch()
    await wrapper.find('button').trigger('click')
    await settle()

    calls[0]!.options.oncomplete(postcodeData({ roadAddress: '', autoRoadAddress: '' }))
    await settle()

    expect(wrapper.emitted('update:addressRoad')).toBeUndefined()
    expect(wrapper.emitted('update:zonecode')).toBeUndefined()
    expect(dialog()).not.toBeNull()
    expect(dialog()!.querySelector('[data-tone="warning"]')?.textContent).toContain('도로명 주소가 있는 결과를 선택해 주세요')
  })

  it('kakao 네임스페이스가 없으면 daum.Postcode를 쓴다', async () => {
    const calls = installFakePostcode('daum')
    const wrapper = await mountSearch()
    await wrapper.find('button').trigger('click')
    await settle()
    expect(calls).toHaveLength(1)
  })

  it('로드 실패 → 모달에 경고 + 다시 시도·직접 입력 · 다시 시도 성공 시 임베드', async () => {
    const { scripts } = interceptScriptInsert()
    vi.spyOn(console, 'warn').mockImplementation(() => {})
    const wrapper = await mountSearch()

    await wrapper.find('button').trigger('click')
    expect(scripts).toHaveLength(1)
    expect(scripts[0]!.src).toBe('https://t1.kakaocdn.net/mapjsapi/bundle/postcode/prod/postcode.v2.js')
    scripts[0]!.dispatchEvent(new Event('error'))
    await settle()

    expect(dialog()!.querySelector('[data-tone="warning"]')?.textContent).toContain('직접 입력해 주세요')
    expect(dialogButton('직접 입력')).toBeTruthy()

    const calls = installFakePostcode('kakao')
    dialogButton('다시 시도').click()
    await settle()
    expect(calls).toHaveLength(1)
    expect(dialog()!.contains(calls[0]!.element)).toBe(true)
    expect(dialog()!.querySelector('[data-tone="warning"]')).toBeNull()
  })

  it('로드 실패 → 직접 입력 → manualEntry true · 모달 닫힘 · 폼의 검색 버튼·요약 숨김', async () => {
    const { scripts } = interceptScriptInsert()
    vi.spyOn(console, 'warn').mockImplementation(() => {})
    const wrapper = await mountSearch()

    await wrapper.find('button').trigger('click')
    scripts[0]!.dispatchEvent(new Event('error'))
    await settle()
    dialogButton('직접 입력').click()
    await settle()

    expect(wrapper.emitted('update:manualEntry')?.at(-1)).toEqual([true])
    expect(dialog()).toBeNull()
    await wrapper.setProps({ manualEntry: true })
    expect(wrapper.findAll('button')).toHaveLength(0)
    expect(wrapper.find('[data-testid="address-summary"]').exists()).toBe(false)
  })
})

describe('loadPostcode — 타임아웃', () => {
  it('8초 안에 로드되지 않으면 실패하고, 다음 호출은 script를 새로 넣는다', async () => {
    vi.useFakeTimers()
    const { scripts } = interceptScriptInsert()

    const first = loadPostcode()
    expect(loadPostcode()).toBe(first)
    vi.advanceTimersByTime(8000)
    await expect(first).rejects.toThrow('시간 초과')

    const second = loadPostcode()
    expect(scripts).toHaveLength(2)
    scripts[1]!.dispatchEvent(new Event('error'))
    await expect(second).rejects.toThrow('로드 실패')
  })
})
