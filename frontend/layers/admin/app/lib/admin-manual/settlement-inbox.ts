import type { ManualSection } from '~/types/manual'

// 정산 · 운영 인박스. 근거: recon §2 A7·A8 · settlements/index.vue:137,213,226 · settlements/[id].vue:215,246-253 · admin-risk-confirm.ts:15-28 ·
// admin-settlement-view.ts:44-67 · AdminSettlementRegenerateDialog.vue:68 · SettlementCreationService.java:41-59,116-124 ·
// inbox.vue:191 · InboxDeadlinePolicy.java:18-33 · SellerDelayInboxSource.java:18-19 · admin-inbox-view.ts:136-142 · inbox-snooze.ts:39-45
export const ADMIN_SETTLEMENT_SECTION: ManualSection = {
  id: 'settlement',
  title: '정산',
  summary: '월별 셀러 정산을 만들고(편입) 검수한 뒤 확정하고 지급완료를 표시합니다. 확정하면 셀러에게 공개되고, 확정·지급완료는 되돌릴 수 없습니다.',
  steps: [
    {
      id: 'list',
      title: '정산 만들기와 목록',
      paragraphs: [
        '정산은 매월 1일 이후 전월분이 자동으로 만들어집니다. 지난 달을 직접 만들 때는 "정산 생성"을 누릅니다. 구매확정 매출이 있는 셀러마다 정산이 하나씩 만들어지고, 이미 만들어진 셀러는 건너뜁니다.',
        '정산에는 그 달 말까지 구매확정된 품목과 완료된 환불, 앞선 마이너스 정산의 이월분이 들어갑니다. 위 합계 카드는 선택한 월 전체 기준입니다.',
      ],
      captureId: 'settlement-list',
      captureAlt: '정산 내역 화면',
      callouts: [
        { number: 1, region: 'create', label: '정산 생성', description: '고른 연·월의 정산을 만듭니다.' },
        { number: 2, region: 'status', label: '상태 필터', description: '확정 대기·확정·지급완료로 좁힙니다.' },
        { number: 3, region: 'totals', label: '합계', description: '매출·수수료·환불·이월 차감·지급액 합계입니다.' },
        { number: 4, region: 'open', label: '상세', description: '정산 상세에서 확정·지급완료를 처리합니다.' },
      ],
      warnings: [],
      rules: [
        {
          // R16 SettlementCreationService.java:116-124 · R55
          title: '정산 생성 조건',
          items: [
            '말일이 지난 달만 만들 수 있습니다. 이번 달을 고르면 "마감되지 않은 정산 기간입니다"가 나옵니다.',
            '셀러·기간마다 정산은 하나입니다. 열린 불일치가 있는 주문은 정산에서 보류됩니다.',
          ],
        },
      ],
    },
    {
      id: 'confirm',
      title: '확정 대기 정산 확정',
      paragraphs: [
        '정산 상세에서 금액과 계좌를 확인하고 "확정"을 누릅니다. 확정 전에는 "재생성"으로 같은 셀러·기간을 다시 집계할 수 있습니다(재집계할 대상이 없으면 삭제만 됩니다).',
      ],
      captureId: 'settlement-pending',
      captureAlt: '확정 대기 정산 상세',
      callouts: [
        { number: 1, region: 'status', label: '상태', description: '확정 대기입니다.' },
        { number: 2, region: 'confirm', label: '확정', description: '확인 창을 거쳐 확정합니다.' },
        { number: 3, region: 'regenerate', label: '재생성', description: '사유를 적고 다시 집계합니다.' },
        { number: 4, region: 'net', label: '지급액', description: '매출 − 수수료 − 환불 − 이월 차감입니다.' },
      ],
      warnings: [
        // 되돌릴 수 없는 동작 #15
        { title: '정산 확정', body: '확정하면 즉시 셀러에게 공개되고 SMS가 발송됩니다. 확정한 정산은 확정 대기로 되돌리거나 재생성할 수 없습니다.' },
      ],
      rules: [
        // R17 RegenerateSettlementRequest.java:10
        { title: '재생성 입력', items: ['재생성 사유는 필수이고 200자까지 입력합니다.'] },
      ],
    },
    {
      id: 'pay',
      title: '지급완료 표시',
      paragraphs: [
        '지급을 마친 확정 정산은 "지급완료"로 표시합니다. 지급 시점의 셀러 주 정산계좌가 함께 기록됩니다.',
      ],
      captureId: 'settlement-confirmed',
      captureAlt: '확정 정산 상세',
      callouts: [
        { number: 1, region: 'status', label: '상태', description: '확정입니다.' },
        { number: 2, region: 'pay', label: '지급완료', description: '지급할 수 없으면 비활성이고 아래에 이유가 나옵니다.' },
        { number: 3, region: 'bank', label: '정산계좌', description: '지금 셀러의 주 정산계좌입니다.' },
      ],
      warnings: [
        // 되돌릴 수 없는 동작 #16
        { title: '정산 지급완료', body: '지급완료로 표시한 정산은 되돌릴 수 없습니다. 계좌와 금액을 확인한 뒤 누릅니다.' },
      ],
      rules: [
        {
          // R41 admin-settlement-view.ts:44-67
          title: '지급완료가 비활성일 때',
          items: [
            '"지급액이 음수라 지급할 수 없습니다(차감 이월 필요)." — 다음 정산에서 차감됩니다.',
            '"셀러의 주 정산계좌가 없어 지급할 수 없습니다." — 회원 관리의 셀러 상세에서 계좌를 등록합니다.',
          ],
        },
      ],
    },
  ],
}

export const ADMIN_INBOX_SECTION: ManualSection = {
  id: 'inbox',
  title: '운영 인박스',
  summary: '여러 화면에 흩어진 처리 대기를 기한 순으로 모아 보고, 상세 패널에서 바로 처리합니다. 당장 처리할 수 없는 항목은 보류하고, 처리가 늦은 셀러에게 독촉 문자를 보냅니다.',
  steps: [
    {
      id: 'overview',
      title: '오늘 할 일 보기',
      paragraphs: [
        '오늘 탭은 기한이 오늘이거나 지난 항목, 예정 탭은 내일 이후 항목입니다. 유형 칩으로 한 종류만 볼 수 있고, 칩의 숫자는 건수입니다. 항목을 누르면 오른쪽 상세 패널에서 승인·답변·상태 변경 같은 처리를 하거나 "원래 화면에서 열기"로 해당 화면으로 갑니다.',
      ],
      captureId: 'inbox-overview',
      captureAlt: '관리자 운영 인박스 전체 화면',
      callouts: [
        { number: 1, region: 'tabs', label: '오늘 / 예정', description: '기한 기준으로 나뉩니다.' },
        { number: 2, region: 'chips', label: '유형 칩', description: '클레임 접수·1:1 문의·정산 확정 등 10가지 유형입니다.' },
        { number: 3, region: 'origin', label: '원래 화면에서 열기', description: '그 일을 처리하는 원래 화면으로 이동합니다.' },
        { number: 4, region: 'snooze', label: '보류', description: '정한 시각까지 목록에서 숨깁니다.' },
      ],
      warnings: [],
      rules: [
        {
          // InboxDeadlinePolicy.java:18-33 · SellerDelayInboxSource.java:18-19 · R33 InboxQueryService.java:35
          title: '유형별 처리 기한',
          items: [
            '24시간: 클레임 접수 · 1:1 문의 · 상품 승인 · 정합성 불일치.',
            '48시간: 클레임 후속 처리. 72시간: 셀러 입점 심사. 7일: 장기 배송중.',
            '정산 확정은 지급 예정일 3일 전, 정산 지급은 지급 예정일 당일까지입니다.',
            '셀러 지연은 기한이 지난 건이 있는 셀러라 항상 오늘 탭에 있습니다.',
            '목록에는 최대 200건까지 보이며, 넘치면 "항목이 많아 일부만 표시합니다. 유형을 골라 좁혀 보세요."가 나옵니다.',
          ],
        },
      ],
    },
    {
      id: 'snooze',
      title: '보류하기',
      paragraphs: [
        '외부 확인이나 고객 회신을 기다리느라 지금 처리할 수 없는 항목은 보류합니다. 사유와 다시 표시할 시각을 정하면 그 시각까지 목록에서 숨겨집니다.',
      ],
      captureId: 'inbox-snooze',
      captureAlt: '인박스 보류 창',
      callouts: [
        { number: 1, region: 'reason', label: '사유 빠른 선택', description: '외부 확인 대기·고객 회신 대기 중에서 고르거나 직접 입력합니다.' },
        { number: 2, region: 'preset', label: '다시 표시할 시각', description: '1시간 뒤·오늘 18:00·내일 09:00·직접 선택 중에서 고릅니다.' },
        { number: 3, region: 'submit', label: '보류', description: '보류를 저장합니다.' },
      ],
      warnings: [],
      rules: [
        // R19 InboxSnoozeRequest.java:20-22
        { title: '보류 입력', items: ['사유는 1~200자입니다.', '다시 표시할 시각은 지금 이후여야 합니다("지금 이후의 시각을 선택하세요.").', '"오늘 18:00"은 18시 전에만 고를 수 있습니다.'] },
      ],
    },
    {
      id: 'nudge',
      title: '처리가 늦은 셀러 독촉',
      paragraphs: [
        '셀러 지연 유형에는 기한이 지난 발송 대기·상품 Q&A 미답변이 있는 셀러가 모입니다. 셀러를 체크하면 위에 "선택 독촉" 바가 나타나고, 확인 창을 거쳐 독촉 문자를 보냅니다. 결과는 셀러마다 발송·발송 실패·연락처 없음·24시간 내 독촉함·지연 없음 중 하나로 나옵니다.',
      ],
      captureId: 'inbox-nudge',
      captureAlt: '셀러 지연 항목을 체크해 독촉 바가 나타난 화면',
      callouts: [
        { number: 1, region: 'check', label: '셀러 선택', description: '독촉할 셀러를 체크합니다.' },
        { number: 2, region: 'count', label: '선택 수', description: '고른 셀러 수와 최대 수(20곳)입니다.' },
        { number: 3, region: 'send', label: '선택 독촉', description: '확인 창을 거쳐 문자를 보냅니다.' },
      ],
      warnings: [
        // 되돌릴 수 없는 동작 #22
        { title: '독촉 문자 발송', body: '보낸 문자는 되돌릴 수 없습니다. 문자에는 기한이 지난 발송 대기·상품 Q&A 미답변 건수가 들어갑니다.' },
      ],
      rules: [
        // R31 · R50
        { title: '독촉 제한', items: ['한 번에 최대 20곳까지 고를 수 있습니다.', '24시간 안에 이미 독촉한 셀러에게는 보내지 않습니다.', '클레임 승인과 셀러 독촉은 한 번에 함께 고를 수 없습니다.'] },
      ],
    },
  ],
}
