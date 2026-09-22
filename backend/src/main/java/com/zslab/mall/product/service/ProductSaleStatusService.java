package com.zslab.mall.product.service;

import com.zslab.mall.audit.enums.AuditLogAction;
import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.audit.service.AuditRecorder;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import com.zslab.mall.product.entity.Product;
import com.zslab.mall.product.enums.ProductStatus;
import com.zslab.mall.product.enums.SaleStopSource;
import com.zslab.mall.product.exception.ProductInvalidStateException;
import com.zslab.mall.product.exception.ProductNotFoundException;
import com.zslab.mall.product.repository.ProductRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상품 판매 상태 전환 Application Service(Track 71·운영자 주도). SALE → STOPPED(판매중지)·STOPPED → SALE(재판매)만 다룬다.
 * 승인 워크플로(PENDING → SALE/REJECTED)는 {@link ProductApprovalService}가 담당하며 본 서비스는 그 패턴(비관적 락·
 * IllegalStateException → {@link ProductInvalidStateException}(422) 흡수·같은 트랜잭션 감사 로그)을 그대로 준용한다.
 *
 * <p>승인과 달리 같은 상태 재요청(SALE→SALE·STOPPED→STOPPED)은 멱등 no-op이 아니라 전이 위반(422)으로 거부한다
 * (운영자 오조작 감지·확정 사항).
 *
 * <p><b>주체 기록(Track 96-5·D-206)</b>: 관리자 중지는 {@code saleStopSource=ADMIN}으로 기록되어 셀러가 재판매할 수 없다. 관리자 재판매는
 * 주체(ADMIN·SELLER)와 무관하게 허용하며 null로 돌린다. 감사 before/after에 status·saleStopSource를 함께 적재한다.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class ProductSaleStatusService {

    private final ProductRepository productRepository;
    private final AuditRecorder auditRecorder;

    /**
     * 상품 판매 상태를 전환한다. 실제 전이 시 status 변경을 같은 트랜잭션에서 감사 로그(UPDATE)로 적재한다.
     *
     * @param publicId     대상 상품 public_id(prd_)
     * @param target       목표 상태(SALE·STOPPED만·DTO @Pattern으로 선검증됨)
     * @param auditContext 감사 행위자 컨텍스트(운영자)
     * @return 전환된 Product
     * @throws ProductNotFoundException     상품 미존재(404)
     * @throws ProductInvalidStateException 허용 외 전이·같은 상태 재요청(422)
     */
    public Product changeSaleStatus(String publicId, ProductStatus target, AuditContext auditContext) {
        Product product = productRepository.findByPublicIdForUpdate(publicId)
                .orElseThrow(() -> new ProductNotFoundException(
                        "상품을 찾을 수 없습니다: publicId=" + publicId));

        ProductStatus before = product.getStatus();
        Map<String, Object> beforeSnapshot = saleStateSnapshot(product);
        try {
            if (target == ProductStatus.STOPPED) {
                product.stopSale(SaleStopSource.ADMIN);
            } else {
                product.resumeSale();
            }
        } catch (IllegalStateException exception) {
            throw new ProductInvalidStateException("판매 상태를 전환할 수 없습니다: " + exception.getMessage());
        }
        auditRecorder.record(auditContext, AuditLogAction.UPDATE, PolymorphicTargetType.PRODUCT, product.getId(),
                beforeSnapshot, saleStateSnapshot(product));
        log.info("[Product] 판매 상태 전환 완료({} → {}): publicId={}", before, product.getStatus(), publicId);
        return product;
    }

    /** 감사용 판매 상태 스냅샷(status·saleStopSource). saleStopSource는 null 가능이라 {@code Map.of} 대신 LinkedHashMap을 쓴다. */
    static Map<String, Object> saleStateSnapshot(Product product) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("status", product.getStatus().name());
        snapshot.put("saleStopSource", product.getSaleStopSource() == null ? null : product.getSaleStopSource().name());
        return snapshot;
    }
}
