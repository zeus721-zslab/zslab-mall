import type { Component, Ref } from 'vue'
import { SKIN_STATE_KEY } from '~/lib/constants/skin'
import { DEFAULT_SKIN, resolveSkinView, type SkinName } from '~/skins/registry'
import type { SkinViewName } from '~/skins/contracts/views'

/** 이번 요청의 스킨명. plugins/skin.ts가 요청 단위로 1회 결정하고 SSR 페이로드로 CSR에 넘긴다. */
export function useSkinName(): Ref<SkinName> {
  return useState<SkinName>(SKIN_STATE_KEY, () => DEFAULT_SKIN)
}

/** 현재 스킨의 뷰. 없으면 parent → classic 순으로 대체한다. */
export function useSkinView(view: SkinViewName): Component {
  return resolveSkinView(useSkinName().value, view)
}
