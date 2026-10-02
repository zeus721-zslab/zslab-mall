import type { ManualSection } from '~/types/manual'

// 주문·배송. 근거: recon §2 A1 · admin/orders/index.vue:103 · orders/[id].vue:171-194,322-329 · AdminOrderQueryService.java:327-348 ·
// admin-order-view.ts:32-34,79-81,103-110 · AdminOrderCancelDialog.vue:108 · deliveries.vue:131 · AdminDeliveryTrackingDialog.vue:90
export const ADMIN_ORDER_DELIVERY_SECTION: ManualSection = {
  id: 'order-delivery',
  title: '주문·배송',
  summary: '주문을 찾아 발송·배송완료를 처리하고, 필요하면 주문을 취소합니다. 발송된 배송의 송장은 배송 관리에서 고칩니다.',
  steps: [
    {
      id: 'list',
      title: '주문 찾기와 발송·배송완료',
      paragraphs: [
        '전체 주문 화면에서 주문번호·주문자·상품명이나 기간·상태로 주문을 찾습니다. 행 끝 메뉴에서 결제완료 품목은 발송 처리, 배송중 품목은 배송완료 처리를 합니다.',
        '발송 처리는 택배사와 송장번호를 넣으면 품목이 바로 배송중으로 바뀝니다. 취소는 이 목록이 아니라 주문 상세에서 합니다.',
      ],
      captureId: 'order-list',
      captureAlt: '전체 주문 화면에서 결제완료 주문 행의 메뉴를 연 화면',
      callouts: [
        { number: 1, region: 'filters', label: '검색·필터', description: '주문번호·주문자·상품명, 주문일, 주문·결제·배송 상태로 좁힙니다.' },
        { number: 2, region: 'status', label: '주문상태', description: '결제완료를 고르면 발송할 주문만 남습니다.' },
        { number: 3, region: 'detail', label: '상세', description: '주문 상세(취소·결제·클레임 처리)로 이동합니다.' },
        { number: 4, region: 'ship', label: '발송 처리', description: '택배사와 송장번호를 입력하는 창을 엽니다.' },
      ],
      warnings: [
        // 되돌릴 수 없는 동작 #2 · #3(OrderItemStatus.java:52-53 · DeliveryStatus.java:26)
        { title: '발송 처리', body: '발송한 품목은 결제완료로 되돌릴 수 없습니다. 송장을 잘못 넣었다면 배송 관리의 송장 정정으로 고칩니다.' },
        { title: '배송완료 처리', body: '배송완료로 바꾼 배송은 배송중으로 되돌릴 수 없고, 이때부터 구매확정 기한(배송완료 후 7일)을 계산합니다.' },
      ],
      rules: [
        {
          title: '처리 조건',
          items: [
            // R1 Delivery.java:40-41 · R37 OrderShippingService.java:83-84 · R28 AdminOrderQueryService.java:77
            '송장번호는 영문·숫자·하이픈(-)만 써서 8~20자로 입력합니다.',
            '발송은 결제완료 품목만 됩니다. 취소·반품·교환 요청이 진행 중인 품목은 발송되지 않습니다.',
            '검색어는 50자까지 입력할 수 있습니다.',
          ],
        },
      ],
    },
    {
      id: 'detail',
      title: '주문 상세 보기',
      paragraphs: [
        '주문 상세에는 그 주문에서 지금 할 수 있는 처리만 버튼으로 나옵니다. 결제완료 품목이 있으면 발송 처리, 배송중 배송이 있으면 배송완료 처리, 취소할 수 있는 품목이 있으면 주문 취소가 보입니다. 취소·미결제 종료로 끝난 주문에는 버튼이 없습니다.',
        '아래쪽 품목마다 클레임이 있으면 승인·거부와 처리 이력을 볼 수 있습니다. 결제 표의 "취소 처리" 버튼은 환불이 결제액 전액만큼 끝났는데 결제가 아직 결제완료로 남아 있을 때만 나타납니다.',
      ],
      captureId: 'order-detail',
      captureAlt: '결제완료 주문의 주문 상세 화면',
      callouts: [
        { number: 1, region: 'cancel', label: '주문 취소', description: '취소할 품목과 사유를 고르는 창을 엽니다.' },
        { number: 2, region: 'ship', label: '발송 처리', description: '결제완료 품목이 있을 때만 보입니다.' },
        { number: 3, region: 'items', label: '품목', description: '품목별 상태와 클레임 승인·거부·처리 이력이 있습니다.' },
        { number: 4, region: 'back', label: '목록으로', description: '보던 주문 목록으로 돌아갑니다.' },
      ],
      warnings: [
        // 되돌릴 수 없는 동작 #4 · R4 · R59(AdminPaymentCancelDialog.vue:45,54)
        { title: '결제 취소 처리', body: '결제를 취소 상태로 바꾸면 되돌릴 수 없습니다. 사유(200자 이내)가 필요하고, 전액 환불이 끝나지 않은 결제는 "전액 환불이 완료된 결제만 취소 처리됩니다. 상태가 바뀌지 않았습니다."가 나오며 그대로 둡니다.' },
      ],
      rules: [],
    },
    {
      id: 'cancel',
      title: '주문 취소',
      paragraphs: [
        '주문 취소 창에서 취소할 품목을 고르고 사유를 선택합니다. 결제가 끝난 주문은 고른 품목마다 취소 클레임이 만들어져 바로 승인되고 환불이 진행됩니다. 결제 전 주문은 주문 전체가 미결제 종료로 끝나고 잡아 둔 재고가 풀립니다.',
      ],
      captureId: 'order-cancel',
      captureAlt: '주문 취소 창',
      callouts: [
        { number: 1, region: 'items', label: '취소할 품목', description: '결제완료·상품준비중 품목만 고를 수 있습니다.' },
        { number: 2, region: 'reason', label: '취소 사유', description: '반드시 고릅니다.' },
        { number: 3, region: 'detail', label: '상세 사유', description: '500자까지 남길 수 있습니다.' },
        { number: 4, region: 'confirm', label: '확인', description: '누르면 바로 취소됩니다.' },
      ],
      warnings: [
        // 되돌릴 수 없는 동작 #1
        { title: '주문 취소', body: '취소한 주문은 되돌릴 수 없습니다. 결제가 끝난 품목은 취소와 함께 환불이 진행됩니다.' },
      ],
      rules: [
        {
          // R3 AdminOrderCancelRequest.java:16 · R36 admin-order-view.ts:32-34
          title: '입력 조건',
          items: [
            '품목을 1개 이상 고르지 않으면 "취소할 품목을 1개 이상 선택하세요."가, 사유가 없으면 "취소 사유를 선택하세요."가 나옵니다.',
            '취소할 수 있는 품목이 없으면 "취소 가능한 품목이 없습니다."가 나옵니다.',
          ],
        },
      ],
    },
    {
      id: 'deliveries',
      title: '배송 조회',
      paragraphs: [
        '배송 관리는 주문이 아니라 배송 단위로 보여 줍니다. 조회 범위를 바꾸면 원 발송 말고도 교환·재발송, 반품·교환 회수 배송을 볼 수 있습니다. 행을 누르면 배송 상세가 열립니다.',
      ],
      captureId: 'delivery-list',
      captureAlt: '배송 관리 화면에서 배송중 배송을 본 화면',
      callouts: [
        { number: 1, region: 'scope', label: '조회 범위', description: '원 발송·교환·재발송·반품·교환 회수·전체 중에서 고릅니다.' },
        { number: 2, region: 'status', label: '배송상태', description: '배송준비·배송중·배송완료로 좁힙니다.' },
        { number: 3, region: 'trackingNo', label: '송장번호', description: '택배사와 함께 표시됩니다.' },
        { number: 4, region: 'copy', label: '복사', description: '송장번호를 클립보드에 복사합니다.' },
      ],
      warnings: [],
      rules: [],
    },
    {
      id: 'tracking',
      title: '송장 정정',
      paragraphs: [
        '배송 상세의 "송장 수정"으로 택배사·송장번호를 고칩니다. 배송 상태는 바뀌지 않고, 사유는 처리 이력에 남습니다. 배송중인 배송만 고칠 수 있습니다.',
      ],
      captureId: 'delivery-tracking',
      captureAlt: '관리자 송장 정정 창',
      callouts: [
        { number: 1, region: 'carrier', label: '택배사', description: '바뀐 택배사가 있으면 다시 고릅니다.' },
        { number: 2, region: 'trackingNo', label: '송장번호', description: '올바른 번호로 다시 입력합니다.' },
        { number: 3, region: 'reason', label: '사유', description: '왜 고치는지 적습니다.' },
        { number: 4, region: 'confirm', label: '수정', description: '입력을 모두 채우면 누를 수 있습니다.' },
      ],
      warnings: [],
      rules: [
        {
          // R2 AdminDeliveryTrackingCorrectionRequest.java:18 · R39 AdminDeliveryController.java:108-110 · R1
          title: '정정 조건',
          items: [
            '배송중 상태의 배송만 고칠 수 있습니다. 그 밖의 배송은 "송장 수정" 버튼이 비활성입니다.',
            '사유는 필수이고 200자까지 입력할 수 있습니다.',
            '송장번호는 영문·숫자·하이픈(-)만 써서 8~20자로 입력합니다.',
          ],
        },
      ],
    },
  ],
}
