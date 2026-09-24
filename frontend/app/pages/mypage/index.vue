<script setup lang="ts">
import { MYPAGE_HUB_MENU_ITEMS } from '~/lib/constants/mypage-menu'
import { orderStatusLabel } from '~/lib/constants/order'
import { formatDateTime } from '~/lib/utils/datetime'
import type { MypageMenu, MypagePageVm } from '~/skins/contracts/mypage'

// BUYER 전용 — 미인증/비-BUYER는 buyer 미들웨어가 /login으로 유도한다.
definePageMeta({ middleware: 'buyer' })

// 허브 메뉴(FE-13 → FE-72 메뉴 정의 단일 소스). 로그아웃은 AppHeader가 담당하므로 여기 중복 배치하지 않는다.
const menus: MypageMenu[] = MYPAGE_HUB_MENU_ITEMS

// 홈 데이터(인사·주문 현황·기본 배송지·최근 주문)는 스킨이 mypageHome을 선언했을 때만 조회한다(FE-72). classic은 추가 조회 0건.
const homeData = useSkinNeeds('mypageHome') ? useMypageHome() : undefined

// 세션 만료 등으로 서버가 401이면 로그인으로 유도(미들웨어는 진입 UX만·실인가 SoT는 서버).
if (homeData) {
  watch(
    homeData.errors,
    (errors) => {
      if (errors.some((fetchError) => (fetchError as { statusCode?: number } | undefined)?.statusCode === 401)) {
        navigateTo(`/login?redirect=${encodeURIComponent('/mypage')}`)
      }
    },
    { immediate: true },
  )
}

function createHomeVm(data: ReturnType<typeof useMypageHome>) {
  return {
    profile: data.profile,
    addresses: data.addresses,
    defaultAddress: data.defaultAddress,
    summary: data.summary,
    summaryStages: data.summaryStages,
    recentOrders: data.recentOrders,
    orderStatusLabel,
    formatDateTime,
  }
}

useSeoMeta({ title: '마이페이지 · zslab-mall', description: 'zslab-mall 마이페이지' })

const vm: MypagePageVm = reactive({ menus, home: homeData ? createHomeVm(homeData) : undefined })
</script>

<template>
  <component :is="useSkinView('MypageView')" :vm="vm" />
</template>
