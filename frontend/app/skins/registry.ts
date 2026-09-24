import type { Component } from 'vue'
import { classicSkin, classicViews } from './classic'
import type { SkinViewName, SkinViews } from './contracts/views'

/** 등록된 스킨 이름 SoT. 여기 없는 값(쿠키·env)은 무시하고 classic으로 대체한다. */
export const SKIN_NAMES = ['classic'] as const
export type SkinName = (typeof SKIN_NAMES)[number]
export const DEFAULT_SKIN: SkinName = 'classic'

/**
 * 스킨 정의. views에 없는 뷰는 parent → classic 순으로 대체한다.
 * classic은 전 뷰를 정적 import로 채운다. 이후 스킨은 바꾸는 뷰만 defineAsyncComponent로 채워 별도 청크로 분리한다.
 */
export interface SkinDefinition {
  parent?: SkinName
  views: Partial<SkinViews>
}

const skins: Record<SkinName, SkinDefinition> = {
  classic: classicSkin,
}

export function isSkinName(value: string | null | undefined): value is SkinName {
  return SKIN_NAMES.some((name) => name === value)
}

export function resolveSkinView(skin: SkinName, view: SkinViewName): Component {
  // parent 순환 설정이 있어도 무한 루프에 빠지지 않도록 방문한 스킨은 건너뛴다.
  const visited = new Set<SkinName>()
  let current: SkinName | undefined = skin
  while (current !== undefined && !visited.has(current)) {
    visited.add(current)
    const found = skins[current].views[view]
    if (found) return found
    current = skins[current].parent
  }
  return classicViews[view]
}
