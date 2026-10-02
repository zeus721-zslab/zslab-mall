import type { ManualSection } from '~/types/manual'

// 클레임 조회 · 상품·재고 · 상품 Q&A · 정산 · 통계 · 운영 인박스 · 계정 설정.
// 근거: recon §1-3·§2 · seller/claims/index.vue:75 · claims/[id].vue:146-182 · products/index.vue:98 · seller-product-sale-status.ts:17,45,47 ·
// SellerProductSaleStatusCard.vue:89,106-108 · products/new.vue:31 · seller-product-form.ts:210-251 · inventory.vue:85 · SellerInventoryAdjustDialog.vue:101-135 ·
// questions.vue:71 · SellerProductQuestionAnswerDialog.vue:91 · settlements/index.vue:76 · seller-settlement-view.ts:57 · settlements/[id].vue:172-204 ·
// stats/sales.vue:168,206-210 · seller-stats.ts:13 · inbox.vue:172 · InboxDeadlinePolicy.java:22,27-28 · bank-account.vue:100 · seller-bank-account.ts:17-21
export const SELLER_CLAIM_SECTION: ManualSection = {
  id: 'claim',
  title: '클레임 조회',
  summary: '내 품목에 들어온 취소·반품·교환 요청과 처리 진행 상황을 봅니다. 승인·거부·검수는 관리자가 처리하고, 셀러 화면에는 처리 버튼이 없습니다.',
  steps: [
    {
      id: 'list',
      title: '클레임 목록',
      paragraphs: [
        '클레임 화면에서 유형(취소·반품·교환)과 상태(요청·승인·거부·완료), 요청일로 클레임을 좁힙니다. 진행 중인 클레임에는 경과 일수가, 환불이 붙은 클레임에는 환불 상태가 보입니다.',
      ],
      captureId: 'claim-list',
      captureAlt: '셀러 클레임 목록',
      callouts: [
        { number: 1, region: 'type', label: '유형', description: '취소·반품·교환으로 좁힙니다.' },
        { number: 2, region: 'status', label: '상태', description: '요청·승인·거부·완료로 좁힙니다.' },
        { number: 3, region: 'statusChip', label: '처리 상태', description: '지금 클레임이 어느 단계인지 보여 줍니다.' },
        { number: 4, region: 'open', label: '상세', description: '진행 과정과 첨부 사진을 봅니다.' },
      ],
      warnings: [],
      rules: [],
    },
    {
      id: 'detail',
      title: '진행 상황 보기',
      paragraphs: [
        '클레임 상세의 진행 상태는 요청 접수 → 관리자 처리 → 완료 순서로 보입니다. 반품 요청 중 상품 불량·오배송 사유에는 구매자가 첨부한 사진이 함께 나옵니다.',
      ],
      captureId: 'claim-detail',
      captureAlt: '셀러 클레임 상세의 진행 상태',
      callouts: [
        { number: 1, region: 'status', label: '상태', description: '클레임 상태입니다.' },
        { number: 2, region: 'timeline', label: '진행 상태', description: '단계별 시각이 보입니다. 승인·거부·검수는 관리자가 처리합니다.' },
      ],
      warnings: [],
      rules: [],
    },
  ],
}

export const SELLER_PRODUCT_SECTION: ManualSection = {
  id: 'product',
  title: '상품·재고',
  summary: '상품을 등록·수정하고, 판매중지·재판매와 품절을 직접 처리합니다. 재고 수량은 재고 화면에서 입고·출고로 바꿉니다. 등록한 상품은 관리자 승인 뒤 판매됩니다.',
  steps: [
    {
      id: 'list',
      title: '상품 목록과 판매중지·재판매',
      paragraphs: [
        '상품 화면에서 내 상품을 상태·카테고리로 좁히고 정렬합니다. 판매중 상품은 행 메뉴에서 "판매중지", 내가 중지한 상품은 "재판매"를 고릅니다.',
        '판매중지하면 구매자 목록에서 바로 숨겨지고 장바구니 담기·주문이 막힙니다(진행 중 주문은 영향 없음). 재판매하면 다시 노출됩니다. 판매중지는 이 화면에서 직접 되돌릴 수 있습니다.',
      ],
      captureId: 'product-list',
      captureAlt: '셀러 상품 목록에서 판매 관리 메뉴를 연 화면',
      callouts: [
        { number: 1, region: 'create', label: '상품 등록', description: '새 상품 등록 화면으로 갑니다.' },
        { number: 2, region: 'status', label: '상태 필터', description: '판매중·승인대기·판매중지·거부됨으로 좁힙니다.' },
        { number: 3, region: 'edit', label: '수정', description: '상품 수정 화면(판매 관리 카드 포함)으로 갑니다.' },
        { number: 4, region: 'saleAction', label: '판매중지 / 재판매', description: '확인 창을 거쳐 판매 상태를 바꿉니다.' },
      ],
      warnings: [],
      rules: [
        // R43 seller-product-sale-status.ts:17
        { title: '재판매가 안 될 때', items: ['관리자가 판매중지한 상품은 재판매할 수 없습니다. "관리자가 판매중지한 상품입니다. 재판매는 운영자에게 문의하세요."가 보입니다.'] },
      ],
    },
    {
      id: 'sale-card',
      title: '판매 관리 카드와 품절',
      paragraphs: [
        '상품 수정 화면 맨 위 판매 관리 카드에서 판매중지·재판매와 수동 품절을 바꿉니다. 이 카드의 변경은 바로 반영되고, 아래 폼에서 저장하지 않은 수정 내용은 그대로 남습니다. 옵션별 품절은 옵션 조합표의 품절 스위치로 따로 정합니다.',
        '승인 전 상품은 판매중지·재판매 버튼 대신 "승인·거부는 관리자가 처리합니다"가 보입니다.',
      ],
      captureId: 'product-sale-card',
      captureAlt: '셀러 상품 수정 화면의 판매 관리 카드',
      callouts: [
        { number: 1, region: 'status', label: '판매 상태', description: '판매중·판매중지 등 지금 상태입니다.' },
        { number: 2, region: 'action', label: '판매중지 / 재판매', description: '확인 창을 거쳐 바꿉니다.' },
        { number: 3, region: 'soldOut', label: '수동 품절', description: '누르는 즉시 품절로 바뀝니다.' },
      ],
      warnings: [],
      rules: [],
    },
    {
      id: 'new',
      title: '상품 등록',
      paragraphs: [
        '기본정보·이미지·옵션을 입력하고 등록하면 승인대기 상태로 만들어집니다. 관리자가 승인하면 판매 화면에 노출됩니다. 등록 뒤 수정은 승인 없이 바로 반영됩니다.',
      ],
      captureId: 'product-new',
      captureAlt: '셀러 상품 등록 화면의 기본정보',
      callouts: [
        { number: 1, region: 'category', label: '카테고리', description: '필수입니다.' },
        { number: 2, region: 'price', label: '판매가', description: '0 이상의 정수로 입력합니다.' },
        { number: 3, region: 'name', label: '상품명', description: '필수이고 200자까지입니다.' },
      ],
      warnings: [],
      rules: [
        {
          // R9 · R11 seller-product-form.ts:210-251
          title: '입력 확인 문구',
          items: [
            '"카테고리를 선택하세요." · "상품명을 입력하세요." · "판매가는 0 이상의 정수여야 합니다." · "같은 옵션 조합이 중복됩니다."',
            '판매가는 10억 원까지, 초기 재고는 1,000,000개까지입니다. 옵션 코드 50자, SKU 100자, 옵션값 100자까지 입력합니다.',
          ],
        },
      ],
    },
    {
      id: 'inventory',
      title: '재고 보기',
      paragraphs: [
        '재고 화면은 옵션 단위로 보유·예약·가용 수량을 보여 줍니다. 주문에 묶인 예약 수량은 가용에서 빠집니다. 가용 재고가 1~5개면 인박스의 재고 임박에 올라옵니다.',
      ],
      captureId: 'inventory-list',
      captureAlt: '셀러 재고 화면',
      callouts: [
        { number: 1, region: 'keyword', label: '검색', description: '상품명이나 SKU로 찾습니다.' },
        { number: 2, region: 'available', label: '가용', description: '지금 팔 수 있는 수량입니다. 0이면 품절로 보입니다.' },
        { number: 3, region: 'inbound', label: '입고', description: '보유·가용을 늘립니다.' },
        { number: 4, region: 'outbound', label: '출고', description: '보유·가용을 줄입니다.' },
      ],
      warnings: [],
      rules: [],
    },
    {
      id: 'adjust',
      title: '입고·출고 처리',
      paragraphs: [
        '입고·출고 창에서 수량과 사유를 입력합니다. 입고는 수량만큼 보유·가용이 늘고, 출고는 줄어듭니다. 출고는 가용 수량을 넘을 수 없습니다. 수량이 크면 한 번 더 확인합니다.',
      ],
      captureId: 'inventory-adjust',
      captureAlt: '셀러 입고 처리 창',
      callouts: [
        { number: 1, region: 'item', label: '대상 옵션', description: '현재 보유·가용 수량이 함께 보입니다.' },
        { number: 2, region: 'quantity', label: '수량', description: '1 이상의 정수입니다.' },
        { number: 3, region: 'reason', label: '사유', description: '필수입니다.' },
        { number: 4, region: 'confirm', label: '확인', description: '누르면 바로 반영됩니다.' },
      ],
      warnings: [],
      rules: [
        // R8 SellerInventoryMarkInboundRequest.java:17-18
        { title: '입출고 입력', items: ['수량은 한 번에 1,000,000개까지, 사유는 255자까지입니다.'] },
      ],
    },
  ],
}

export const SELLER_QNA_SECTION: ManualSection = {
  id: 'qna',
  title: '상품 Q&A 답변',
  summary: '내 상품에 남겨진 구매자 질문에 답변합니다. 답변 창은 근거와 함께 답안 초안을 보여 줍니다. 답변은 상품 페이지에 공개됩니다.',
  steps: [
    {
      id: 'list',
      title: '답변할 질문 찾기',
      paragraphs: [
        '상품 질문 화면은 처음에 미답변만 보여 줍니다. 인박스 기준으로 상품 Q&A 미답변은 48시간 안에 답변합니다.',
      ],
      captureId: 'question-list',
      captureAlt: '셀러 상품 질문 목록',
      callouts: [
        { number: 1, region: 'answered', label: '답변 여부', description: '미답변·답변완료·전체 중에서 고릅니다.' },
        { number: 2, region: 'unanswered', label: '미답변 표시', description: '아직 답하지 않은 질문입니다.' },
        { number: 3, region: 'answer', label: '답변하기', description: '답변 창을 엽니다. 답변한 질문은 "답변 수정"입니다.' },
      ],
      warnings: [],
      rules: [],
    },
    {
      id: 'answer',
      title: '답안 초안으로 답변하기',
      paragraphs: [
        '답변 창을 열 때마다 답안 초안을 새로 계산합니다. 근거가 있으면 근거 목록과 "초안 사용" 버튼이 나오고, 눌러야 입력란이 채워집니다. 근거가 없으면 "근거 부족 — 직접 작성해 주세요."가 나옵니다. 답변은 등록한 뒤에도 고칠 수 있습니다.',
      ],
      captureId: 'question-answer',
      captureAlt: '셀러 상품 질문 답변 창',
      callouts: [
        { number: 1, region: 'question', label: '질문', description: '구매자가 남긴 질문입니다.' },
        { number: 2, region: 'draft', label: '답안 초안', description: '근거 목록과 "초안 사용" 버튼이 있습니다.' },
        { number: 3, region: 'content', label: '답변', description: '1,000자까지 입력합니다. 상품 페이지에 공개됩니다.' },
        { number: 4, region: 'confirm', label: '등록', description: '답변을 저장합니다.' },
      ],
      warnings: [],
      rules: [
        // R12 · R45 seller-error-message.ts:42
        { title: '답변 조건', items: ['답변은 필수이고 1,000자까지입니다.', '관리자가 숨긴 질문에는 답변할 수 없습니다("숨김 처리된 질문에는 답변할 수 없습니다.").'] },
      ],
    },
  ],
}

export const SELLER_SETTLEMENT_SECTION: ManualSection = {
  id: 'settlement',
  title: '정산 조회',
  summary: '운영자가 확정·지급완료한 월별 정산을 봅니다. 확정 전 정산은 목록에 나오지 않고, 건수만 안내됩니다.',
  steps: [
    {
      id: 'list',
      title: '정산 목록',
      paragraphs: [
        '정산 화면에는 확정·지급완료된 정산만 나옵니다. 확정 대기 정산이 있으면 위에 "확정 대기 정산 n건이 있습니다. 운영자가 확정하면 이 목록에 표시됩니다"가 보입니다(대시보드 "정산 예정"과 같은 건수).',
      ],
      captureId: 'settlement-list',
      captureAlt: '셀러 정산 목록',
      callouts: [
        { number: 1, region: 'pending', label: '확정 대기 안내', description: '아직 확정되지 않은 정산 건수입니다.' },
        { number: 2, region: 'net', label: '지급액', description: '그 달 정산의 지급액입니다.' },
        { number: 3, region: 'status', label: '상태', description: '확정 또는 지급완료입니다.' },
        { number: 4, region: 'open', label: '상세', description: '금액 구성과 품목을 봅니다.' },
      ],
      warnings: [],
      rules: [],
    },
    {
      id: 'detail',
      title: '정산 상세',
      paragraphs: [
        '정산 상세에서 매출·수수료·환불·이월 차감·지급액과 정산계좌를 확인합니다. 확정된 정산은 지급예정일에 주 정산계좌로 지급됩니다. 지급액이 음수인 정산은 다음 정산에서 차감 이월됩니다.',
        '주 정산계좌가 없으면 "등록된 주 정산계좌가 없습니다. 지급 전 운영자에게 계좌 등록을 요청하세요."가 보입니다.',
      ],
      captureId: 'settlement-detail',
      captureAlt: '셀러 정산 상세',
      callouts: [
        { number: 1, region: 'status', label: '상태', description: '확정 또는 지급완료입니다.' },
        { number: 2, region: 'net', label: '지급액', description: '매출 − 수수료 − 환불 − 이월 차감입니다.' },
        { number: 3, region: 'bank', label: '정산계좌', description: '지급완료 정산은 지급 당시 계좌, 그 밖에는 현재 주 정산계좌입니다.' },
      ],
      warnings: [],
      rules: [],
    },
  ],
}

export const SELLER_STATS_SECTION: ManualSection = {
  id: 'stats',
  title: '통계',
  summary: '내 품목 기준 매출·주문·클레임·상품 통계를 기간별로 봅니다.',
  steps: [
    {
      id: 'sales',
      title: '통계 보기',
      paragraphs: [
        '통계는 매출, 주문·클레임, 상품 세 탭입니다. 매출 탭은 기간·집계 단위·비교 기간을 바꿔 보고, 상품·옵션·카테고리로 나눠 CSV로 내보낼 수 있습니다. 매출은 결제 완료 기준 내 품목 금액 합이고, 수수료·정산 예정액은 정산 화면에서 봅니다.',
        '주문·클레임 탭은 결제→발송→배송완료 흐름과 처리 소요시간·클레임률·환불률을, 상품 탭은 판매 상위·하위, 미판매 상품, 재고 회전, 현재 품절 옵션을 보여 줍니다.',
      ],
      captureId: 'stats-sales',
      captureAlt: '셀러 매출 통계',
      callouts: [
        { number: 1, region: 'tabs', label: '통계 탭', description: '매출, 주문·클레임, 상품을 오갑니다.' },
        { number: 2, region: 'period', label: '기간 선택', description: '최근 7일·30일·3개월·올해·직접 지정과 단위·비교를 고릅니다.' },
        { number: 3, region: 'summary', label: '요약', description: '매출·환불·순매출·주문 수 등 기간 합계입니다.' },
      ],
      warnings: [],
      rules: [
        // R29 seller-stats.ts:13 · SellerSalesStatsQueryService.java:49,122
        { title: '기간 조건', items: ['기간은 최대 365일까지 조회할 수 있습니다.'] },
      ],
    },
  ],
}

export const SELLER_INBOX_SECTION: ManualSection = {
  id: 'inbox',
  title: '운영 인박스',
  summary: '발송 대기·상품 Q&A 미답변·장기 배송중·재고 임박 항목을 기한 순으로 모아 보고, 상세 패널에서 바로 처리합니다.',
  steps: [
    {
      id: 'overview',
      title: '오늘 할 일 보기',
      paragraphs: [
        '오늘 탭은 기한이 오늘이거나 지난 항목, 예정 탭은 내일 이후 항목입니다. 항목을 누르면 오른쪽 상세 패널에서 발송 처리·답변·배송완료·입고를 바로 하거나 "원래 화면에서 열기"로 해당 화면으로 갑니다.',
      ],
      captureId: 'inbox-overview',
      captureAlt: '셀러 운영 인박스',
      callouts: [
        { number: 1, region: 'tabs', label: '오늘 / 예정', description: '기한 기준으로 나뉩니다.' },
        { number: 2, region: 'chips', label: '유형 칩', description: '장기 배송중·발송 대기·Q&A 미답변·재고 임박 4가지입니다.' },
        { number: 3, region: 'origin', label: '원래 화면에서 열기', description: '주문·Q&A·배송·재고 화면으로 이동합니다.' },
        { number: 4, region: 'snooze', label: '보류', description: '정한 시각까지 목록에서 숨깁니다.' },
      ],
      warnings: [],
      rules: [
        // InboxDeadlinePolicy.java:22,27-28
        { title: '유형별 처리 기한', items: ['발송 대기·Q&A 미답변 48시간, 장기 배송중 7일입니다. 재고 임박은 기한이 없습니다.'] },
      ],
    },
    {
      id: 'snooze',
      title: '보류하기',
      paragraphs: [
        '외부 확인이나 고객 회신을 기다리는 항목은 보류합니다. 사유와 다시 표시할 시각을 정하면 그 시각까지 목록에서 숨겨집니다.',
      ],
      captureId: 'inbox-snooze',
      captureAlt: '셀러 인박스 보류 창',
      callouts: [
        { number: 1, region: 'reason', label: '사유 빠른 선택', description: '외부 확인 대기·고객 회신 대기 중에서 고르거나 직접 입력합니다.' },
        { number: 2, region: 'preset', label: '다시 표시할 시각', description: '1시간 뒤·오늘 18:00·내일 09:00·직접 선택 중에서 고릅니다.' },
        { number: 3, region: 'submit', label: '보류', description: '보류를 저장합니다.' },
      ],
      warnings: [],
      rules: [
        // R19
        { title: '보류 입력', items: ['사유는 1~200자이고, 다시 표시할 시각은 지금 이후여야 합니다.'] },
      ],
    },
  ],
}

export const SELLER_SETTINGS_SECTION: ManualSection = {
  id: 'settings',
  title: '정산계좌',
  summary: '정산 지급을 받을 계좌를 확인하고 등록합니다. 등록은 셀러 대표만 할 수 있습니다.',
  steps: [
    {
      id: 'bank-account',
      title: '정산계좌 등록',
      paragraphs: [
        '등록된 계좌는 끝 4자리만 보이고, 주 정산계좌에 표시가 붙습니다. 첫 번째로 등록한 계좌가 주 정산계좌가 되며, 계좌 변경·수정은 운영자에게 문의합니다.',
        '대표가 아닌 구성원에게는 등록 폼 대신 "정산계좌 등록은 셀러 대표(OWNER)만 할 수 있습니다. 조회만 가능합니다."가 보입니다.',
      ],
      captureId: 'bank-account',
      captureAlt: '셀러 정산계좌 화면',
      callouts: [
        { number: 1, region: 'list', label: '등록된 계좌', description: '은행과 계좌 끝 4자리, 주 정산계좌 표시입니다.' },
        { number: 2, region: 'register', label: '계좌 등록', description: '대표에게만 보입니다. 다른 역할은 조회 전용 안내가 보입니다.' },
      ],
      warnings: [],
      rules: [
        // R24 · R49
        { title: '계좌 입력', items: ['계좌번호는 숫자와 하이픈(-)만 6~30자입니다("계좌번호는 숫자와 하이픈만 허용합니다."). 예금주는 50자까지입니다.'] },
      ],
    },
  ],
}
