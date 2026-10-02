import type { ManualSection } from '~/types/manual'

// 주문·배송 처리. 주문 화면 = 결제완료 품목 발송 · 배송 화면 = 배송완료·송장 정정(orders/index.vue:95 · deliveries/index.vue:109-116)
export const SELLER_ORDER_DELIVERY_SECTION: ManualSection = {
  id: 'order-delivery',
  title: '주문·배송 처리',
  summary: '결제가 끝난 품목에 송장을 등록해 발송하고, 배송 중인 건의 송장을 고치거나 배송완료로 처리합니다. 발송은 주문 화면에서, 배송완료와 송장 정정은 배송 화면에서 합니다.',
  steps: [
    {
      id: 'paid-items',
      title: '발송할 품목 찾기',
      paragraphs: [
        // 품목 단위 조회 · 발송 버튼 PAID만(seller-order.ts:74) · InboxDeadlinePolicy.java:27
        '주문 화면은 주문을 품목 단위로 보여 줍니다. 품목 상태를 "결제완료"로 거르면 지금 발송해야 할 품목만 남습니다. 인박스 기준으로 발송 대기는 48시간 안에 처리합니다.',
        '발송 버튼은 결제완료 품목에만 있습니다. 상세 아이콘을 누르면 배송지와 배송 정보를 함께 볼 수 있습니다.',
      ],
      captureId: 'order-paid-list',
      captureAlt: '셀러 주문 화면에서 품목 상태를 결제완료로 거른 목록',
      callouts: [
        { number: 1, region: 'statusFilter', label: '품목 상태 필터', description: '"결제완료"를 고르면 발송 대기 품목만 보입니다.' },
        { number: 2, region: 'orderNo', label: '주문번호', description: '누르면 이 품목의 주문 상세로 이동합니다.' },
        { number: 3, region: 'detail', label: '상세 보기', description: '배송지·배송 정보를 확인합니다.' },
        { number: 4, region: 'ship', label: '발송', description: '송장을 입력하는 발송 처리 창을 엽니다.' },
      ],
      warnings: [],
      rules: [],
    },
    {
      id: 'ship',
      title: '송장을 입력해 발송',
      paragraphs: [
        // SellerShipmentDialog.vue:97
        '발송 처리 창에서 택배사를 고르고 송장번호를 입력한 뒤 "발송"을 누릅니다. 송장을 등록하는 순간 품목은 배송중으로 바뀌고, 이후 이 품목은 배송 화면에서 관리합니다.',
      ],
      captureId: 'order-ship-dialog',
      captureAlt: '발송 처리 창에서 택배사와 송장번호를 입력한 화면',
      callouts: [
        { number: 1, region: 'item', label: '발송할 품목', description: '상품명·옵션·수량을 확인합니다.' },
        { number: 2, region: 'carrier', label: '택배사', description: 'CJ대한통운·한진택배·우체국택배·로젠택배 중에서 고릅니다.' },
        { number: 3, region: 'trackingNo', label: '송장번호', description: '오른쪽 아래 숫자는 입력한 글자 수와 최대 길이입니다.' },
        { number: 4, region: 'confirm', label: '발송', description: '누르면 바로 배송중으로 바뀝니다.' },
      ],
      warnings: [
        // 되돌릴 수 없는 동작 #2(OrderItemStatus.java:52-53)
        { title: '발송 처리', body: '발송한 품목을 결제완료로 되돌릴 수는 없습니다. 송장을 잘못 넣었다면 배송 화면의 "송장 정정"으로 고칩니다.' },
      ],
      rules: [
        {
          // R1 Delivery.java:40-41 · R37 OrderShippingService.java:83-84 · R58 seller-error-message.ts:17-45
          title: '송장 입력 규칙',
          items: [
            '송장번호는 영문·숫자·하이픈(-)만 써서 8~20자로 입력합니다.',
            '결제완료 품목만 발송할 수 있습니다. 다른 상태면 "발송은 결제완료 품목만" 안내가 나옵니다. 취소·반품·교환 요청이 진행 중인 품목도 발송되지 않습니다.',
          ],
        },
      ],
    },
    {
      id: 'deliveries',
      title: '배송 중인 건 관리',
      paragraphs: [
        // deliveries/index.vue:112-116 · seller-delivery.ts:13-18,64
        '배송 화면에는 발송한 배송만 나옵니다(아직 발송하지 않은 결제완료 품목은 주문 화면에 있습니다). 조회 범위를 바꾸면 교환·재발송이나 반품·교환 회수 배송도 볼 수 있습니다.',
        '배송중인 행의 ⋮ 메뉴에서 "배송완료 처리"와 "송장 정정"을 고릅니다. 송장번호 옆 복사 버튼으로 번호를 바로 복사할 수 있습니다.',
      ],
      captureId: 'delivery-menu',
      captureAlt: '셀러 배송 화면에서 배송중 행의 메뉴를 연 화면',
      callouts: [
        { number: 1, region: 'scope', label: '조회 범위', description: '원 발송·교환·재발송·반품·교환 회수·전체 중에서 고릅니다.' },
        { number: 2, region: 'copy', label: '송장번호 복사', description: '송장번호를 클립보드에 복사합니다.' },
        { number: 3, region: 'markDelivered', label: '배송완료 처리', description: '빨간 글씨는 되돌릴 수 없는 동작이라는 표시입니다.' },
        { number: 4, region: 'correct', label: '송장 정정', description: '택배사·송장번호를 고칩니다.' },
      ],
      warnings: [],
      rules: [
        {
          // R38 seller-delivery-view.ts:31-34 · R39 seller-delivery.ts:64
          title: '메뉴가 보이는 조건',
          items: [
            '배송중 상태의 배송에만 메뉴가 있습니다.',
            '배송완료 처리는 원 발송 배송만 할 수 있습니다. 교환품 발송 같은 클레임 배송은 관리자가 처리합니다.',
          ],
        },
      ],
    },
    {
      id: 'correct-tracking',
      title: '잘못 넣은 송장 고치기',
      paragraphs: [
        // SellerDeliveryTrackingDialog.vue:47,122-124
        '송장 정정 창에서 택배사와 송장번호를 다시 입력하고, 고치는 이유를 적은 뒤 "수정"을 누릅니다. 정정은 배송중인 동안에만 할 수 있습니다.',
      ],
      captureId: 'delivery-tracking-dialog',
      captureAlt: '셀러 송장 정정 창',
      callouts: [
        { number: 1, region: 'carrier', label: '택배사', description: '바뀐 택배사가 있으면 다시 고릅니다.' },
        { number: 2, region: 'trackingNo', label: '송장번호', description: '올바른 번호로 다시 입력합니다.' },
        { number: 3, region: 'reason', label: '정정 사유', description: '왜 고치는지 적습니다. 필수입니다.' },
        { number: 4, region: 'confirm', label: '수정', description: '택배사·송장번호·사유를 모두 입력해야 누를 수 있습니다.' },
      ],
      warnings: [],
      rules: [
        {
          // R2 SellerDeliveryTrackingCorrectionRequest.java:18 · R1
          title: '정정 입력 규칙',
          items: [
            '정정 사유는 필수이고 200자까지 입력할 수 있습니다.',
            '송장번호는 영문·숫자·하이픈(-)만 써서 8~20자로 입력합니다.',
          ],
        },
      ],
    },
    {
      id: 'mark-delivered',
      title: '배송완료로 처리',
      paragraphs: [
        // delivery.ts:58-61 · ReturnWindowPolicy.java:23 · DeliveryAutoCompleteScheduler.java:20
        '택배가 도착했는데 상태가 배송중으로 남아 있으면 "배송완료 처리"로 직접 바꿉니다. 택배 조회 결과로 자동 배송완료가 되기도 합니다.',
        '배송완료가 되면 그때부터 구매확정 기한을 계산합니다. 배송완료 후 7일이 지나면 자동으로 구매확정됩니다.',
      ],
      captureId: 'delivery-mark-delivered',
      captureAlt: '셀러 배송완료 처리 확인 창',
      callouts: [
        { number: 1, region: 'notice', label: '확인 안내', description: '배송완료 처리 후 일어나는 일과 되돌릴 수 없다는 안내입니다.' },
        { number: 2, region: 'target', label: '처리할 배송', description: '상품명·택배사·송장번호·주문번호를 확인합니다.' },
        { number: 3, region: 'confirm', label: '배송완료', description: '빨간 테두리 버튼은 되돌릴 수 없는 동작입니다.' },
      ],
      warnings: [
        // 되돌릴 수 없는 동작 #3(DeliveryStatus.java:26)
        { title: '배송완료 처리', body: '배송완료로 바꾼 배송은 배송중으로 되돌릴 수 없고, 송장도 더 고칠 수 없습니다.' },
      ],
      rules: [],
    },
  ],
}
