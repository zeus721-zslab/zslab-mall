import { toast } from 'vue-sonner'
import { ADMIN_SEMANTIC_TOAST_TYPE, type AdminSemantic } from '#layers/admin/app/lib/constants/semantic'

/**
 * 관리자 토스트(FE-25 보강·vue-sonner). API는 의미 색상 4종(danger·warning·success·info·constants/semantic.ts)이며 sonner variant로는
 * danger→error로 매핑한다(richColors 유지). success·info 3s / warning·danger 5s. action은 "상세 보기"처럼 후속 UI를 여는 버튼.
 * Toaster(AdminToaster.vue)는 관리자 레이아웃에만 있어 사용자 영역에서는 호출해도 렌더되지 않는다(관리자 한정 로드).
 */
export type AdminToastType = AdminSemantic

export interface AdminToastAction {
  label: string
  onClick: () => void
}

export const ADMIN_TOAST_DURATION_MS: Record<AdminToastType, number> = {
  success: 3000,
  info: 3000,
  warning: 5000,
  danger: 5000,
}

export interface AdminToastOptions {
  action?: AdminToastAction
}

export function useAdminToast() {
  function show(type: AdminToastType, message: string, options: AdminToastOptions = {}): void {
    toast[ADMIN_SEMANTIC_TOAST_TYPE[type]](message, {
      duration: ADMIN_TOAST_DURATION_MS[type],
      action: options.action ? { label: options.action.label, onClick: options.action.onClick } : undefined,
    })
  }

  return {
    show,
    success: (message: string, options?: AdminToastOptions) => show('success', message, options),
    warning: (message: string, options?: AdminToastOptions) => show('warning', message, options),
    danger: (message: string, options?: AdminToastOptions) => show('danger', message, options),
    info: (message: string, options?: AdminToastOptions) => show('info', message, options),
  }
}
