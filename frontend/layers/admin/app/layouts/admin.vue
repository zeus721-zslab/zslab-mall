<script setup lang="ts">
import { useDisplay } from 'vuetify'
import { useAdminLeaveGuard } from '#layers/admin/app/lib/admin-leave-guard'
import { useFirstPaintGate } from '#layers/admin/app/lib/first-paint-gate'

// 관리자 셸 레이아웃(FE-22·FE-22c Vuetify·FE-22f Argon형). 사용자 셸(default.vue의 AppHeader/AppFooter)과 분리.
// 각 페이지가 definePageMeta({ layout:'admin', middleware:['admin','vuetify'] })로 지정한다(D-4). Vuetify는 vuetify 미들웨어가 렌더 전에 설치한다.
// 관리자 이탈(뒤로가기 등 클라이언트 이동으로 /admin 밖으로) 시 전체 새로고침해 Vuetify 전역 스타일 누수를 막는다(D-15).
useAdminLeaveGuard()

// 첫 페인트 게이트(FE-22h): 밴드·사이드바·상단바·콘텐츠를 같은 프레임에 렌더한다.
const ready = useFirstPaintGate()

// 사이드바 열림: 데스크톱은 열린 채 시작, 모바일은 닫힌 채 시작·상단바 토글로 연다.
const { mdAndUp } = useDisplay()
const sidebarOpen = ref<boolean>(mdAndUp.value)
</script>

<template>
  <v-app>
    <template v-if="ready">
      <!-- 상단 컬러 밴드(300px): 투명 상단바·페이지 헤더 뒤, 콘텐츠 카드는 밴드 위로 겹친다 -->
      <div class="adm-band adm-band-soft" aria-hidden="true" />
      <AdminSidebar v-model="sidebarOpen" />
      <AdminTopbar @toggle-sidebar="sidebarOpen = !sidebarOpen" />
      <v-main class="adm-main">
        <div class="adm-content">
          <slot />
        </div>
      </v-main>
      <!-- 관리자 토스트(FE-25 보강·vue-sonner). 관리자 레이아웃에만 두어 사용자 entry에 포함되지 않는다 -->
      <AdminToaster />
    </template>
  </v-app>
</template>
