<script setup lang="ts">
useSeoMeta({
  title: 'zslab-mall',
  description: '엄선한 신상품을 만나보세요. zslab-mall에서 취향에 맞는 상품을 시원하게 둘러보세요.',
})
</script>

<template>
  <div>
    <HomeHero />
    <div class="py-14 md:py-20">
      <div class="mx-auto mb-8 max-w-[1240px] px-4 md:px-6">
        <h2 class="text-2xl font-bold tracking-tight text-gray-900 md:text-3xl">신상품</h2>
      </div>
      <HomeProductGrid />
    </div>
  </div>
</template>
