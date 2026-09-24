<script setup lang="ts">
import type { HomePageVm } from '~/skins/contracts/home'

useSeoMeta({
  title: 'zslab-mall',
  description: '엄선한 신상품을 만나보세요. zslab-mall에서 취향에 맞는 상품을 시원하게 둘러보세요.',
})

// 메인 큐레이션은 스킨이 homeCuration을 선언했을 때만 조회한다(FE-69). classic HomeView는 vm 없이 렌더한다(추가 조회 0).
const vm: HomePageVm | undefined = useSkinNeeds('homeCuration') ? reactive(useHomeCuration()) : undefined
</script>

<template>
  <component :is="useSkinView('HomeView')" :vm="vm" />
</template>
