import { ADMIN_HOME_PATH } from '#layers/admin/app/lib/constants/auth'

/**
 * 관리자 영역 이탈 판정(FE-22c D-15). 대상 경로가 /admin 자신 또는 /admin/ 하위가 아니면 전체 새로고침 대상이다.
 * Vuetify 전역 스타일(html·* 리셋·!important 유틸)은 SPA 이동으로 해제되지 않아 사용자 화면을 깨뜨리므로 문서 재로드로 버린다.
 */
export function shouldReloadOnLeave(targetPath: string): boolean {
  return targetPath !== ADMIN_HOME_PATH && !targetPath.startsWith(`${ADMIN_HOME_PATH}/`)
}

/**
 * 관리자 레이아웃 unmount 시 현재 라우트(=이동 대상)가 관리자 밖이면 그 URL로 전체 새로고침한다.
 * unmount 시점엔 router.currentRoute가 이미 대상 라우트로 바뀌어 있다. 관리자 내부 이동(로그아웃 → /admin/login 포함)은 대상이 /admin 하위라 새로고침하지 않는다.
 * 서버 렌더 없음(D-9 ssr:false)이지만 방어적으로 client 한정.
 */
export function useAdminLeaveGuard(): void {
  const router = useRouter()
  onBeforeUnmount(() => {
    if (!import.meta.client) return
    const target = router.currentRoute.value.fullPath
    if (shouldReloadOnLeave(target)) {
      window.location.assign(target)
    }
  })
}
