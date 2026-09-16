<script setup lang="ts">
import { mdiHammerWrench } from '@mdi/js'

// 관리자 메뉴 공통 "준비 중" 플레이스홀더(FE-22·FE-22e 빈 상태 카드). BE 조회 API 트랙이 붙기 전까지 각 라우트 페이지가 이 컴포넌트만 렌더한다.
defineProps<{ title: string }>()
</script>

<template>
  <div>
    <AdminPageHeader :title="title" description="백엔드 조회 API 연동 후 제공되는 메뉴입니다." />
    <v-card data-testid="admin-placeholder">
      <v-card-text class="d-flex flex-column align-center text-center py-12">
        <v-avatar color="surface-variant" size="48" class="mb-4">
          <v-icon :icon="mdiHammerWrench" size="22" class="text-medium-emphasis" />
        </v-avatar>
        <p class="text-subtitle-1 font-weight-medium mb-1">준비 중입니다</p>
        <p class="text-body-2 text-medium-emphasis mb-0">이 화면은 해당 도메인 트랙에서 목록·상세와 함께 구현됩니다.</p>
      </v-card-text>
    </v-card>
  </div>
</template>
