package com.zslab.mall.order.repository;

/**
 * 주문 id → 배송지 스냅샷 스칼라 projection(Track 89-B 관리자 배송 목록·상세 배치 enrich·
 * {@code OrderShippingSnapshotRepository.findProjectionsByOrderIdIn}). 스냅샷 엔티티는 order getter를 노출하지 않아 주문 id 키를
 * 얻을 수 없고, Order 쪽에서 적재하면 OneToOne(mappedBy·LAZY 불가)이 주문마다 추가 SELECT를 내므로 스칼라로 읽는다.
 */
public interface OrderShippingSnapshotProjection {

    Long getOrderId();

    String getRecipientName();

    String getRecipientPhone();

    String getZonecode();

    String getAddressRoad();

    String getAddressJibun();

    String getAddressDetail();

    String getDeliveryMemo();
}
