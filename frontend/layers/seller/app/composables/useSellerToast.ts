import { toast } from 'vue-sonner'
import { SELLER_SEMANTIC_TOAST_TYPE, type SellerSemantic } from '#layers/seller/app/lib/constants/semantic'

/**
 * 셀러 토스트(Track 90-B-3·관리자 useAdminToast 복제·vue-sonner). 의미 색상 4종(danger·warning·success·info)이며 danger→error로 매핑한다.
 * success·info 3s / warning·danger 5s. Toaster(SellerToaster.vue)는 셀러 레이아웃에만 있어 다른 영역에서는 호출해도 렌더되지 않는다.
 */
export type SellerToastType = SellerSemantic

export const SELLER_TOAST_DURATION_MS: Record<SellerToastType, number> = {
  success: 3000,
  info: 3000,
  warning: 5000,
  danger: 5000,
}

export function useSellerToast() {
  function show(type: SellerToastType, message: string): void {
    toast[SELLER_SEMANTIC_TOAST_TYPE[type]](message, { duration: SELLER_TOAST_DURATION_MS[type] })
  }

  return {
    show,
    success: (message: string) => show('success', message),
    warning: (message: string) => show('warning', message),
    danger: (message: string) => show('danger', message),
    info: (message: string) => show('info', message),
  }
}
