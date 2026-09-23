<script setup lang="ts">
// 관리자 도움말(Track 102 FE-64). 자리만 먼저 확보한다 — 매뉴얼 본문은 매뉴얼 단계에서 이 화면에 채운다.
definePageMeta({ layout: 'admin', middleware: ['admin', 'vuetify'] })
useSeoMeta({ title: '도움말 · zslab-mall 관리자' })
</script>

<template>
  <div>
    <AdminPageHeader title="도움말" description="관리자 화면에서 무엇을 어떻게 처리하는지 안내합니다." />
    <v-card>
      <v-card-text class="text-body-2 text-medium-emphasis" data-testid="admin-help-placeholder">
        준비 중입니다.
      </v-card-text>
    </v-card>
  </div>
</template>
