<script setup lang="ts">
definePageMeta({ layout: 'admin', middleware: ['admin', 'vuetify'] })
useSeoMeta({ title: '상품 수정 · zslab-mall 관리자' })

// 상품 수정(FE-26 예정). 목록의 "수정" 진입 라우트만 먼저 두고 FE-26에서 폼을 붙인다. 가드·레이아웃은 다른 관리자 페이지와 동일.
const route = useRoute()
const productPublicId = computed<string>(() => String(route.params.id ?? ''))
</script>

<template>
  <div>
    <AdminPageHeader title="상품 수정" :description="productPublicId" />
    <v-card data-testid="admin-placeholder">
      <v-card-text class="d-flex flex-column align-center text-center py-12">
        <p class="text-subtitle-1 font-weight-medium mb-1">준비 중입니다</p>
        <p class="text-body-2 text-medium-emphasis mb-4">상품 수정 화면은 FE-26에서 제공됩니다.</p>
        <v-btn variant="outlined" to="/admin/products" data-testid="back-to-list">목록으로</v-btn>
      </v-card-text>
    </v-card>
  </div>
</template>
