import type { ManualDocument } from '~/types/manual'
import { ADMIN_ACCOUNT_SECTION } from '#layers/admin/app/lib/admin-manual/account'
import { ADMIN_CLAIM_SECTION } from '#layers/admin/app/lib/admin-manual/claim'
import { ADMIN_FAQ_SECTION, ADMIN_QNA_INQUIRY_SECTION, ADMIN_REVIEW_SECTION } from '#layers/admin/app/lib/admin-manual/customer-service'
import { ADMIN_ORDER_DELIVERY_SECTION } from '#layers/admin/app/lib/admin-manual/order-delivery'
import { ADMIN_PRODUCT_SECTION } from '#layers/admin/app/lib/admin-manual/product'
import { ADMIN_INBOX_SECTION, ADMIN_SETTLEMENT_SECTION } from '#layers/admin/app/lib/admin-manual/settlement-inbox'
import { ADMIN_START_SECTION } from '#layers/admin/app/lib/admin-manual/start'
import { ADMIN_STATS_RECONCILIATION_SECTION } from '#layers/admin/app/lib/admin-manual/stats-reconciliation'

/**
 * 관리자 매뉴얼(C8). 흐름 순서는 정찰 보고서(docs/track-manual/recon-report.md §2) 업무 흐름 순서이고, 맨 앞에 시작하기를 둔다.
 * 각 섹션 본문의 근거(파일:줄)는 섹션 파일 머리 주석과 항목 주석에 있다. 캡처 영역 키는 walkthrough/manual/admin-*.manual.ts와 1:1이다.
 */
export const ADMIN_MANUAL: ManualDocument = {
  role: 'admin',
  title: '관리자 매뉴얼',
  intro: '관리자 화면에서 일을 처리하는 순서를 흐름별로 정리했습니다. 캡처 위 번호에 마우스를 올리거나 Tab으로 옮기면 해당 영역만 밝게 보이고, 아래 설명도 함께 강조됩니다. 캡처를 누르면 크게 볼 수 있습니다.',
  sections: [
    ADMIN_START_SECTION,
    ADMIN_ORDER_DELIVERY_SECTION,
    ADMIN_CLAIM_SECTION,
    ADMIN_PRODUCT_SECTION,
    ADMIN_QNA_INQUIRY_SECTION,
    ADMIN_REVIEW_SECTION,
    ADMIN_FAQ_SECTION,
    ADMIN_SETTLEMENT_SECTION,
    ADMIN_INBOX_SECTION,
    ADMIN_ACCOUNT_SECTION,
    ADMIN_STATS_RECONCILIATION_SECTION,
  ],
}
