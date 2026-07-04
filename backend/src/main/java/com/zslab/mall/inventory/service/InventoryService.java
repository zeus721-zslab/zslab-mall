package com.zslab.mall.inventory.service;

import com.zslab.mall.inventory.entity.Inventory;
import com.zslab.mall.inventory.entity.InventoryHistory;
import com.zslab.mall.inventory.enums.InventoryHistoryChangeType;
import com.zslab.mall.inventory.exception.InventoryInvariantViolationException;
import com.zslab.mall.inventory.repository.InventoryHistoryRepository;
import com.zslab.mall.inventory.repository.InventoryRepository;
import com.zslab.mall.product.entity.Product;
import com.zslab.mall.product.entity.ProductVariant;
import com.zslab.mall.product.exception.ProductVariantNotFoundException;
import com.zslab.mall.product.repository.ProductRepository;
import com.zslab.mall.product.repository.ProductVariantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재고 도메인 행위 진입점(Track 17 D-101 §2·§11). 각 메서드는 {@link InventoryRepository#findByVariantIdForUpdate}로
 * 비관적 락을 획득한 뒤 Aggregate 도메인 행위를 호출한다(D-101 §4 α 비관락 일원화).
 *
 * <p>InventoryHistory 기록 책임(M-11·D-101 §11): on_hand 변동이 있는 commitReservation·restoreStock만 기록하고,
 * reserved만 변동하는 reserve·release는 기록하지 않는다. EXCHANGE 흐름({@link #exchange})은 회수분 복구·신규분 확정을
 * 단일 트랜잭션에서 처리하며 InventoryHistory 2행(RETURN·ORDER)을 기록한다(D-101 §5 갱신 α).
 */
@Service
@Transactional
@RequiredArgsConstructor
public class InventoryService {

    private final InventoryRepository inventoryRepository;
    private final InventoryHistoryRepository inventoryHistoryRepository;
    private final ProductRepository productRepository;
    private final ProductVariantRepository productVariantRepository;

    /**
     * 상품 등록 시 variant의 초기 재고 행을 생성한다(Track 39 provisioning·생성 전용 진입점·기존 adjust/reserve 계열과 분리).
     * {@link Inventory#create}로 on_hand=initialStock·reserved=0·available=initialStock 행을 저장하고, INBOUND 이력 1행을
     * quantity_delta 양수(+initialStock)·referenceType {@code "product"}·referenceId=productId로 기록한다(M-11·D-101 §11·
     * inventory-policy.md §6 INBOUND=입고 정합). initialStock=0도 감사 baseline으로 이력을 남긴다(재고 0 허용·품절 의미 정의는
     * 본 트랙 범위 아님). Seller self-service 입고({@link #markInboundBySeller})와 동일한 INBOUND 유형이며 referenceType만
     * {@code "product"}로 구분한다. 호출부(등록 오케스트레이션) 배선은 후속 트랙 이연.
     */
    public void initializeInventory(Long variantId, Long productId, int initialStock) {
        Inventory inventory = inventoryRepository.save(Inventory.create(variantId, initialStock));
        inventoryHistoryRepository.save(
                InventoryHistory.create(inventory, InventoryHistoryChangeType.INBOUND, initialStock, "product", productId, null));
    }

    /**
     * 재고를 예약한다(E1 OrderPlaced 소비 진입점). on_hand 불변이므로 History를 기록하지 않는다(M-11 정합·D-101 §11).
     */
    public void reserve(Long variantId, int qty) {
        Inventory inventory = inventoryRepository.findByVariantIdForUpdate(variantId)
                .orElseThrow(() -> new InventoryInvariantViolationException("Inventory 미존재: variantId=" + variantId));
        inventory.reserve(qty);
    }

    /**
     * 재고 예약을 해제한다(E3 PaymentFailed 소비 진입점). on_hand 불변이므로 History를 기록하지 않는다(M-11 정합).
     */
    public void release(Long variantId, int qty) {
        Inventory inventory = inventoryRepository.findByVariantIdForUpdate(variantId)
                .orElseThrow(() -> new InventoryInvariantViolationException("Inventory 미존재: variantId=" + variantId));
        inventory.release(qty);
    }

    /**
     * 예약분을 실물 차감으로 확정한다(E2 PaymentCompleted 소비 진입점). on_hand 감소이므로 History ORDER를
     * quantity_delta 음수(-qty)로 기록한다(M-11·D-101 §11).
     */
    public void commitReservation(Long variantId, int qty, String referenceType, Long referenceId) {
        Inventory inventory = inventoryRepository.findByVariantIdForUpdate(variantId)
                .orElseThrow(() -> new InventoryInvariantViolationException("Inventory 미존재: variantId=" + variantId));
        inventory.commitReservation(qty);
        inventoryHistoryRepository.save(
                InventoryHistory.create(inventory, InventoryHistoryChangeType.ORDER, -qty, referenceType, referenceId, null));
    }

    /**
     * 실물 재고를 복구한다(E9 ClaimCompleted 소비 진입점). CANCEL(결제후취소)·RETURN(반품·교환회수) 유형만 허용하며
     * (D-08 M-12), on_hand 증가이므로 History를 quantity_delta 양수(+qty)로 기록한다(M-11·D-101 §11).
     *
     * @throws IllegalArgumentException type이 CANCEL·RETURN이 아닐 때
     */
    public void restoreStock(Long variantId, int qty, InventoryHistoryChangeType type, String referenceType, Long referenceId) {
        if (type != InventoryHistoryChangeType.CANCEL && type != InventoryHistoryChangeType.RETURN) {
            throw new IllegalArgumentException("restoreStock: type은 CANCEL 또는 RETURN이어야 합니다. type=" + type);
        }
        Inventory inventory = inventoryRepository.findByVariantIdForUpdate(variantId)
                .orElseThrow(() -> new InventoryInvariantViolationException("Inventory 미존재: variantId=" + variantId));
        inventory.restoreStock(qty);
        inventoryHistoryRepository.save(
                InventoryHistory.create(inventory, type, qty, referenceType, referenceId, null));
    }

    /**
     * 교환(EXCHANGE)을 처리한다(E9 ClaimCompleted·EXCHANGE 소비 진입점). 회수분은 실물 복구(RETURN)하고, 교환품 신규 발송은
     * 예약 후 즉시 확정하는 2단계 패턴(α·D-101 §5 갱신)으로 차감(ORDER)한다. commitReservation INV-3 가드를 자연 흡수하며
     * (선행 reserve가 quantityReserved를 먼저 채움), §7 InventoryHistoryChangeType.ADJUST 명명 충돌을 회피한다(기조 4 정합).
     * 회수·신규 2 도메인 행위와 InventoryHistory 2행(RETURN·ORDER)을 단일 DB 트랜잭션에서 원자적으로 처리한다
     * (D-08 [갱신 Track17]·M-12·부분 실패 시 자연 롤백).
     *
     * @throws InventoryInvariantViolationException 회수·신규 variant의 Inventory가 없거나 도메인 불변조건(INV-1)을 위반할 때
     */
    public void exchange(Long returnVariantId, int returnQty, Long newVariantId, int newQty, Long claimId) {
        Inventory returnInventory = inventoryRepository.findByVariantIdForUpdate(returnVariantId)
                .orElseThrow(() -> new InventoryInvariantViolationException("Inventory 미존재: variantId=" + returnVariantId));
        returnInventory.restoreStock(returnQty);
        inventoryHistoryRepository.save(
                InventoryHistory.create(returnInventory, InventoryHistoryChangeType.RETURN, returnQty, "claim", claimId, null));

        Inventory newInventory = inventoryRepository.findByVariantIdForUpdate(newVariantId)
                .orElseThrow(() -> new InventoryInvariantViolationException("Inventory 미존재: variantId=" + newVariantId));
        newInventory.reserve(newQty);            // α 1단계: 예약(quantityReserved 선증가)
        newInventory.commitReservation(newQty);  // α 2단계: 확정(reserved·on_hand 동시 차감)
        inventoryHistoryRepository.save(
                InventoryHistory.create(newInventory, InventoryHistoryChangeType.ORDER, -newQty, "claim", claimId, null));
    }

    /**
     * 운영자 수동 재고를 조정한다(Track 21 D-105 §4·InventoryHistoryChangeType.ADJUST 진입점). on_hand 변동이므로 History를
     * quantity_delta 부호 그대로 기록한다(M-11·D-101 §11). referenceType은 운영자 조정을 표기하는 {@code "admin"}이며 특정
     * 주문·클레임 참조가 없으므로 referenceId는 null이다. 응답 조립(after 수치)을 위해 조정된 Inventory를 반환한다
     * (Controller 재조회 회피·{@code registerExchangeShipmentByAdmin} 엔티티 반환 패턴 정합·recon §7).
     *
     * @throws InventoryInvariantViolationException Inventory 미존재 또는 조정 결과 불변조건(INV-1·INV-4) 위반 시
     * @throws IllegalArgumentException quantityDelta가 0일 때
     */
    public Inventory adjustStock(Long variantId, int quantityDelta, String reason) {
        Inventory inventory = inventoryRepository.findByVariantIdForUpdate(variantId)
                .orElseThrow(() -> new InventoryInvariantViolationException("Inventory 미존재: variantId=" + variantId));
        inventory.adjustStock(quantityDelta);
        inventoryHistoryRepository.save(
                InventoryHistory.create(inventory, InventoryHistoryChangeType.ADJUST, quantityDelta, "admin", null, reason));
        return inventory;
    }

    /**
     * Seller 자기 상품 재고를 입고한다(Track 27 D-112·InventoryHistoryChangeType.INBOUND 진입점). 3홉 소유권 검증
     * ({@link #authorizeSellerAccess} variantId→productId→sellerId) 후 on_hand를 qty만큼 증가시킨다. 신규 도메인 행위를
     * 신설하지 않고 {@link Inventory#adjustStock}(+qty)를 재사용하며(C-α2·기조 4·양수 방향 INV-1·INV-4 자연 정합), History를
     * quantity_delta 양수(+qty)·referenceType {@code "seller"}·referenceId=sellerId로 기록한다(M-11·D-101 §11). 응답 조립
     * (after 수치)을 위해 조정된 Inventory를 반환한다({@link #adjustStock} 반환 패턴 정합). E10 InventoryAdjusted는 발행하지
     * 않는다(γ 계승·D-105 §2 Q3).
     *
     * @throws IllegalArgumentException qty가 양수가 아닐 때(→400)
     * @throws ProductVariantNotFoundException 변형 미존재 또는 타 seller 소유일 때(→404·존재 은닉)
     * @throws InventoryInvariantViolationException Inventory 미존재 또는 조정 결과 불변조건(INV-1·INV-4) 위반 시(→422)
     */
    public Inventory markInboundBySeller(Long sellerId, Long variantId, int qty, String reason) {
        if (qty <= 0) {
            throw new IllegalArgumentException("markInboundBySeller: qty는 양수여야 합니다. qty=" + qty);
        }
        authorizeSellerAccess(sellerId, variantId);
        Inventory inventory = inventoryRepository.findByVariantIdForUpdate(variantId)
                .orElseThrow(() -> new InventoryInvariantViolationException("Inventory 미존재: variantId=" + variantId));
        inventory.adjustStock(qty);
        inventoryHistoryRepository.save(
                InventoryHistory.create(inventory, InventoryHistoryChangeType.INBOUND, qty, "seller", sellerId, reason));
        return inventory;
    }

    /**
     * Seller 자기 상품 재고를 출고한다(Track 27 D-112·InventoryHistoryChangeType.OUTBOUND 진입점). 3홉 소유권 검증 후 on_hand를
     * qty만큼 감소시킨다. 신규 도메인 행위를 신설하지 않고 {@link Inventory#adjustStock}(-qty)를 재사용하며(C-α2·기조 4·감소 방향
     * 실물 부족 INV-4·가용 부족 INV-1 자연 차단), History를 quantity_delta 음수(-qty)·referenceType {@code "seller"}·
     * referenceId=sellerId로 기록한다(M-11·D-101 §11). 조정된 Inventory를 반환한다. E10은 발행하지 않는다(γ 계승).
     *
     * @throws IllegalArgumentException qty가 양수가 아닐 때(→400)
     * @throws ProductVariantNotFoundException 변형 미존재 또는 타 seller 소유일 때(→404·존재 은닉)
     * @throws InventoryInvariantViolationException Inventory 미존재 또는 조정 결과 불변조건(INV-1·INV-4) 위반 시(→422)
     */
    public Inventory markOutboundBySeller(Long sellerId, Long variantId, int qty, String reason) {
        if (qty <= 0) {
            throw new IllegalArgumentException("markOutboundBySeller: qty는 양수여야 합니다. qty=" + qty);
        }
        authorizeSellerAccess(sellerId, variantId);
        Inventory inventory = inventoryRepository.findByVariantIdForUpdate(variantId)
                .orElseThrow(() -> new InventoryInvariantViolationException("Inventory 미존재: variantId=" + variantId));
        inventory.adjustStock(-qty);
        inventoryHistoryRepository.save(
                InventoryHistory.create(inventory, InventoryHistoryChangeType.OUTBOUND, -qty, "seller", sellerId, reason));
        return inventory;
    }

    /**
     * Seller 재고 조작 소유권을 검증한다(Track 27 D-112·D-92 Q3 횡단 원칙). Inventory는 seller_id 직접 참조가 없으므로
     * variantId→productId→sellerId 3홉 조회로 소유권을 확인한다(§9-5 실측·INV-6 1:1). 권한 위반은 미존재와 동일하게
     * {@link ProductVariantNotFoundException}(→404)으로 은닉해 타 seller 상품의 존재 여부를 노출하지 않는다(기존 Seller
     * 컨트롤러 full-hiding 패턴 정합·{@code ClaimService.authorizeSellerAccess} 선례). 변형→상품 FK 무결성 위반은
     * {@link IllegalStateException}(→500)으로 구분한다(권한 은닉 대상 아님·ClaimService 선례 정합).
     *
     * @throws ProductVariantNotFoundException 변형 미존재 또는 sellerId가 상품 소유 seller와 불일치할 때
     * @throws IllegalStateException 변형이 참조하는 상품 행이 없을 때(FK 무결성 위반)
     */
    private void authorizeSellerAccess(Long sellerId, Long variantId) {
        ProductVariant variant = productVariantRepository.findById(variantId)
                .orElseThrow(() -> new ProductVariantNotFoundException(
                        "상품 변형을 찾을 수 없습니다: variantId=" + variantId));
        Product product = productRepository.findById(variant.getProductId())
                .orElseThrow(() -> new IllegalStateException(
                        "Product 무결성 위반: variantId=" + variantId + ", productId=" + variant.getProductId()));
        if (!product.getSellerId().equals(sellerId)) {
            // 권한 위반을 미존재로 은닉한다(정보 노출 회피·D-92 Q3).
            throw new ProductVariantNotFoundException("상품 변형을 찾을 수 없습니다: variantId=" + variantId);
        }
    }
}
