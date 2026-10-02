import type { ManualSection } from '~/types/manual'

// 통계·불일치 점검. 근거: recon §2 A10 · stats/sales.vue:187 · stats/orders.vue:97 · stats/members.vue:121 · admin-stats-period-query.ts ·
// reconciliation.vue:80-81,133-137 · ReconciliationIssue.java:92-104 · ReconciliationCheckScheduler.java:15 · admin-reconciliation-view.ts:134-139
export const ADMIN_STATS_RECONCILIATION_SECTION: ManualSection = {
  id: 'stats-reconciliation',
  title: '통계·불일치 점검',
  summary: '매출·주문·회원 통계를 기간별로 보고, 결제·주문·환불 기록이 서로 맞지 않는 건을 확인해 해결 처리합니다.',
  steps: [
    {
      id: 'stats',
      title: '통계 보기',
      paragraphs: [
        '통계는 매출·주문·회원 세 탭입니다. 기간(최근 7일·30일·3개월·올해·직접 지정)과 집계 단위(일·주·월), 비교 기간(직전 기간·전년 동기)을 바꿔 봅니다.',
        '매출 탭은 카테고리·셀러·상품별로 나눠 보고 CSV로 내려받을 수 있습니다. 주문 탭은 결제→발송→배송완료 흐름과 처리 소요시간·클레임률·환불률을, 회원 탭은 가입·등급 분포·재구매와 구매 상위 회원을 보여 줍니다.',
      ],
      captureId: 'stats-sales',
      captureAlt: '매출 통계 화면',
      callouts: [
        { number: 1, region: 'tabs', label: '통계 탭', description: '매출·주문·회원을 오갑니다.' },
        { number: 2, region: 'period', label: '기간 선택', description: '기간·단위·비교를 바꿉니다.' },
        { number: 3, region: 'summary', label: '요약', description: '매출·환불·순매출·주문 수 등 기간 합계입니다.' },
      ],
      warnings: [],
      rules: [
        // R29 StatsPeriod.java:16-20
        { title: '기간 조건', items: ['시작일이 종료일보다 늦으면 조회되지 않습니다.'] },
      ],
    },
    {
      id: 'reconciliation',
      title: '불일치 확인과 해결',
      paragraphs: [
        '불일치 화면에는 PG 통지·환불 처리·하루 1회 정기 점검에서 결제·주문·환불 기록이 서로 맞지 않는 건이 모입니다. 건마다 유형과 사유, 관련 주문번호가 나옵니다. 확인할 건이 없으면 "조건에 맞는 불일치가 없습니다"가 보입니다.',
        '보정은 주문 상세의 결제 취소·클레임 처리처럼 해당 화면에서 하고, 여기서는 확인한 내용을 메모로 남겨 "해결 처리"합니다. 열린 불일치가 있는 주문은 정산에서 보류됩니다.',
      ],
      captureId: 'reconciliation',
      captureAlt: '불일치 화면',
      callouts: [
        { number: 1, region: 'status', label: '상태', description: '확인 필요·해결됨으로 좁힙니다.' },
        { number: 2, region: 'type', label: '유형', description: '불일치 종류로 좁힙니다.' },
        { number: 3, region: 'result', label: '해결 처리', description: '처리 내용을 메모로 남기고 해결합니다. 건이 없으면 빈 안내가 보입니다.' },
      ],
      warnings: [
        // 되돌릴 수 없는 동작 #17
        { title: '불일치 해결', body: '해결 처리한 건은 확인 필요로 되돌릴 수 없습니다. 실제 보정을 마친 뒤 처리합니다.' },
      ],
      rules: [
        // R18 AdminReconciliationIssueResolveRequest.java:8
        { title: '해결 입력', items: ['처리 내용 메모는 필수이고 500자까지입니다("처리 내용을 입력하세요.").'] },
      ],
    },
  ],
}
