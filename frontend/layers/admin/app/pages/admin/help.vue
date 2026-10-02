<script setup lang="ts">
import ManualShell from '~/components/common/manual/ManualShell.vue'
import { ADMIN_MANUAL } from '#layers/admin/app/lib/admin-manual-content'
import captures from '~~/public/manual/admin/captures.json'

// 관리자 매뉴얼(C8). 진입 가드는 기존 admin 미들웨어(관리자 세션 확인 → 없으면 /admin/login) 그대로다.
// 캡처 이미지·좌표는 walkthrough/manual/admin.manual.ts가 public/manual/admin/에 만든다.
definePageMeta({ layout: 'admin', middleware: ['admin', 'vuetify'] })
useSeoMeta({ title: '도움말 · zslab-mall 관리자' })
</script>

<template>
  <ManualShell :document="ADMIN_MANUAL" :captures="captures" />
</template>
