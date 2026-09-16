<script setup lang="ts">
// 공통 페이지 헤더(FE-22e·FE-22f): 상단 컬러 밴드 위에 놓이므로 흰색 텍스트(admin-vuetify.css .adm-page-header). 제목은 굵기로 위계, 설명은 반투명 흰색. 우측 슬롯은 페이지 액션 자리.
defineProps<{ title: string; description?: string }>()
</script>

<template>
  <header class="adm-page-header d-flex align-start justify-space-between flex-wrap ga-4 mb-6">
    <div>
      <h1 class="text-h6 font-weight-bold">{{ title }}</h1>
      <p v-if="description" class="adm-page-header__description text-body-2 mt-1 mb-0">{{ description }}</p>
    </div>
    <div v-if="$slots.actions" class="d-flex align-center ga-2">
      <slot name="actions" />
    </div>
  </header>
</template>
