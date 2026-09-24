<script setup lang="ts">
import type { NuxtError } from '#app'
import type { ErrorPageVm } from '~/skins/contracts/error'

// 전역 에러 화면(FE-74). Nuxt는 에러 상태에서 app.vue 대신 이 파일을 렌더하므로 기본 레이아웃(헤더·푸터·data-skin)을 직접 감싼다.
// 404와 그 외(일시 오류) 두 갈래만 구분하고, 이 화면에서는 API를 호출하지 않는다. 상세 화면의 페이지 안 404(CommonErrorState)는 별개다.
const props = defineProps<{ error: NuxtError }>()

const HTTP_NOT_FOUND = 404
// 관리자·셀러 영역은 구매자 헤더·푸터가 보이지 않도록 레이아웃 없이 본문만 렌더하고, "홈으로"는 각 영역 첫 화면으로 보낸다.
const AREA_HOMES = ['/admin', '/seller'] as const
const router = useRouter()
const route = useRoute()

const notFound = computed<boolean>(() => props.error.status === HTTP_NOT_FOUND)
const areaHome = computed<string | null>(
  () => AREA_HOMES.find((prefix) => route.path === prefix || route.path.startsWith(`${prefix}/`)) ?? null,
)

async function handleGoHome(): Promise<void> {
  await clearError({ redirect: areaHome.value ?? '/' })
}

// 클라이언트 이동이 끝나면 Nuxt 라우터가 에러 상태를 지운다. 주소로 바로 들어와 이전 기록이 없으면 홈으로 보낸다.
async function handleGoBack(): Promise<void> {
  if (window.history.length > 1) {
    router.back()
    return
  }
  await handleGoHome()
}

useSeoMeta({
  title: () => `${notFound.value ? '페이지를 찾을 수 없어요' : '일시적인 오류가 발생했어요'} · zslab-mall`,
})

const vm: ErrorPageVm = reactive({ notFound, handleGoHome, handleGoBack })
</script>

<template>
  <component :is="useSkinView('ErrorView')" v-if="areaHome" :vm="vm" />
  <NuxtLayout v-else name="default">
    <component :is="useSkinView('ErrorView')" :vm="vm" />
  </NuxtLayout>
</template>
