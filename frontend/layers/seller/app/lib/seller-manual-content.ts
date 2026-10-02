import type { ManualDocument } from '~/types/manual'
import { SELLER_ORDER_DELIVERY_SECTION } from '#layers/seller/app/lib/seller-manual/order-delivery'
import { SELLER_START_SECTION } from '#layers/seller/app/lib/seller-manual/start'
import {
  SELLER_CLAIM_SECTION,
  SELLER_INBOX_SECTION,
  SELLER_PRODUCT_SECTION,
  SELLER_QNA_SECTION,
  SELLER_SETTINGS_SECTION,
  SELLER_SETTLEMENT_SECTION,
  SELLER_STATS_SECTION,
} from '#layers/seller/app/lib/seller-manual/work'

/**
 * 셀러 매뉴얼(C8). 정찰 보고서(docs/track-manual/recon-report.md §2) 업무 흐름 중 셀러가 관여하는 흐름만 싣고, 맨 앞에 시작하기를 둔다.
 * 각 섹션 본문의 근거(파일:줄)는 섹션 파일 머리 주석과 항목 주석에 있다. 캡처 영역 키는 walkthrough/manual/seller-*.manual.ts와 1:1이다.
 */
export const SELLER_MANUAL: ManualDocument = {
  role: 'seller',
  title: '셀러 매뉴얼',
  intro: '셀러 화면에서 일을 처리하는 순서를 흐름별로 정리했습니다. 캡처 위 번호에 마우스를 올리거나 Tab으로 옮기면 해당 영역만 밝게 보이고, 아래 설명도 함께 강조됩니다. 캡처를 누르면 크게 볼 수 있습니다.',
  sections: [
    SELLER_START_SECTION,
    SELLER_ORDER_DELIVERY_SECTION,
    SELLER_CLAIM_SECTION,
    SELLER_PRODUCT_SECTION,
    SELLER_QNA_SECTION,
    SELLER_SETTLEMENT_SECTION,
    SELLER_STATS_SECTION,
    SELLER_INBOX_SECTION,
    SELLER_SETTINGS_SECTION,
  ],
}
