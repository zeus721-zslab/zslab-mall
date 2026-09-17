import { resolvePasswordChangeRedirect } from '~/lib/password-change-guard'

/**
 * 비밀번호 변경 강제 전역 미들웨어(Track 84·D-178). 임시 비밀번호로 로그인한 회원(password_change_required 쿠키)은 비밀번호 변경 페이지와
 * 로그인 페이지 외 어디로도 이동하지 못한다. 판정은 순수 함수(password-change-guard)에 두고 여기서는 store 값만 넘긴다.
 */
export default defineNuxtRouteMiddleware((to) => {
  const auth = useAuthStore()
  const redirect = resolvePasswordChangeRedirect({
    path: to.path,
    authenticated: auth.isAuthenticated,
    required: auth.passwordChangeRequired,
  })
  if (redirect && redirect !== to.fullPath) {
    return navigateTo(redirect)
  }
})
