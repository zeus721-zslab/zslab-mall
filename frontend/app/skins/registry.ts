import type { Component } from 'vue'
import { renewSkin, renewViews } from './renew'
import type { SkinViewName, SkinViews } from './contracts/views'

/** 등록된 스킨 이름 SoT. 여기 없는 값(쿠키·env)은 무시하고 기본 스킨(renew)으로 대체한다. */
export const SKIN_NAMES = ['renew'] as const
export type SkinName = (typeof SKIN_NAMES)[number]
export const DEFAULT_SKIN: SkinName = 'renew'

/**
 * 스킨이 페이지·레이아웃에 요청할 수 있는 추가 데이터(FE-69). 선언한 스킨에서만 페이지가 해당 조회를 실행해 vm으로 넘긴다
 * — 선언이 없는 스킨은 추가 조회 0건. layoutHeader = 헤더 상태·동작 · homeCuration = 메인 큐레이션 섹션 ·
 * productList = 번호 페이지 상품 목록·카테고리 탭·검색 결과(FE-74) · productDetailMore = 상품 상세의 셀러 다른 상품(FE-70) ·
 * mypageHome = 마이페이지 홈의 회원명·주문 현황·기본 배송지·최근 주문(FE-72) · signupPasswordConfirm = 회원가입 비밀번호 확인 칸 검사(FE-74).
 */
export const SKIN_NEEDS = ['layoutHeader', 'homeCuration', 'productList', 'productDetailMore', 'mypageHome', 'signupPasswordConfirm'] as const
export type SkinNeed = (typeof SKIN_NEEDS)[number]

/**
 * 스킨 정의. views에 없는 뷰는 parent → 기준 스킨(renew) 순으로 대체한다.
 * renew는 전 뷰를 정적 import로 채운다(FE-75). 이후 스킨(계절 스킨 등)은 바꾸는 뷰만 defineAsyncComponent로 채워 별도 청크로 분리한다.
 * needs는 parent 체인까지 합산한다(물려받은 뷰가 쓰는 데이터도 함께 받도록).
 */
export interface SkinDefinition {
  parent?: SkinName
  views: Partial<SkinViews>
  needs?: readonly SkinNeed[]
}

const skins: Record<SkinName, SkinDefinition> = {
  renew: renewSkin,
}

export function isSkinName(value: string | null | undefined): value is SkinName {
  return SKIN_NAMES.some((name) => name === value)
}

/** skin → parent 순서의 스킨 체인. parent 순환 설정이 있어도 무한 루프에 빠지지 않도록 방문한 스킨은 건너뛴다. */
function skinChain(skin: SkinName): SkinDefinition[] {
  const chain: SkinDefinition[] = []
  const visited = new Set<SkinName>()
  let current: SkinName | undefined = skin
  while (current !== undefined && !visited.has(current)) {
    visited.add(current)
    chain.push(skins[current])
    current = skins[current].parent
  }
  return chain
}

export function resolveSkinView(skin: SkinName, view: SkinViewName): Component {
  for (const definition of skinChain(skin)) {
    const found = definition.views[view]
    if (found) return found
  }
  return renewViews[view]
}

export function skinNeeds(skin: SkinName, need: SkinNeed): boolean {
  return skinChain(skin).some((definition) => definition.needs?.includes(need) ?? false)
}
