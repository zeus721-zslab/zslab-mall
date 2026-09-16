<script setup lang="ts">
import { useAdminLeaveGuard } from '#layers/admin/app/lib/admin-leave-guard'
import { useFirstPaintGate } from '#layers/admin/app/lib/first-paint-gate'

// 관리자 로그인 전용 레이아웃(FE-22c·FE-22f). 사이드바·상단바 없이 v-app + 상단 컬러 밴드만 제공한다(Vuetify 컴포넌트는 v-app 하위여야 함).
// 로그인 페이지에서 사용자 영역으로 클라이언트 이동하는 경우도 이탈 가드로 전체 새로고침한다(D-15).
useAdminLeaveGuard()

// 첫 페인트 게이트(FE-22h): 밴드와 로그인 카드를 같은 프레임에 렌더한다.
const ready = useFirstPaintGate()
</script>

<template>
  <v-app>
    <template v-if="ready">
      <div class="adm-band adm-band-soft" aria-hidden="true" />
      <v-main class="adm-main">
        <v-container class="fill-height" fluid>
          <slot />
        </v-container>
      </v-main>
      <!-- 관리자 토스트(FE-25 보강): 로그인 화면도 같은 알림 채널을 쓴다 -->
      <AdminToaster />
    </template>
  </v-app>
</template>
