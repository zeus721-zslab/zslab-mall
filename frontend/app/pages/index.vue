<script setup lang="ts">
useSeoMeta({
  title: 'zslab-mall',
  description: '엄선한 신상품을 만나보세요. zslab-mall에서 취향에 맞는 상품을 시원하게 둘러보세요.',
})
</script>

<template>
  <component :is="useSkinView('HomeView')" />
</template>
