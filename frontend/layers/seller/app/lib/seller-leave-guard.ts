import { SELLER_HOME_PATH } from '#layers/seller/app/lib/constants/auth'

/**
 * 셀러 영역 이탈 판정(관리자 D-15 동형). 대상 경로가 /seller 자신 또는 /seller/ 하위가 아니면 전체 새로고침 대상이다.
 * Vuetify 전역 스타일(html·* 리셋·!important 유틸)은 SPA 이동으로 해제되지 않아 사용자 화면을 깨뜨리고, 관리자로 SPA 이동하면 관리자
 * ensureVuetify가 자기 플래그 부재로 두 번째 인스턴스를 설치해 셀러·관리자 테마가 한 문서에서 섞인다(recon §10-3) → 문서 재로드로 버린다.
 */
export function shouldReloadOnSellerLeave(targetPath: string): boolean {
  return targetPath !== SELLER_HOME_PATH && !targetPath.startsWith(`${SELLER_HOME_PATH}/`)
}

/**
 * 셀러 레이아웃 unmount 시 현재 라우트(=이동 대상)가 셀러 밖이면 그 URL로 전체 새로고침한다.
 * unmount 시점엔 router.currentRoute가 이미 대상 라우트로 바뀌어 있다. 셀러 내부 이동(로그아웃 → /seller/login 포함)은 새로고침하지 않는다.
 * 서버 렌더 없음(ssr:false)이지만 방어적으로 client 한정.
 */
export function useSellerLeaveGuard(): void {
  const router = useRouter()
  onBeforeUnmount(() => {
    if (!import.meta.client) return
    const target = router.currentRoute.value.fullPath
    if (shouldReloadOnSellerLeave(target)) {
      window.location.assign(target)
    }
  })
}
