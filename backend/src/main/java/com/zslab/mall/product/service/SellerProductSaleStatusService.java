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
import com.zslab.mall.product.exception.ProductStoppedByAdminException;
import com.zslab.mall.product.repository.ProductRepository;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 셀러 판매 상태·수동 품절 셀프 전환 Application Service(Track 96-5·D-206·C-08). SALE → STOPPED(셀러 중지)·STOPPED(셀러 중지) → SALE(재판매)·
 * 상품 단위 수동 품절 on/off만 다룬다. 승인·거부·삭제는 관리자 소관이다.
 *
 * <p>관리자 {@link ProductSaleStatusService}·{@code AdminProductCommandService}는 재사용하지 않고 패턴(비관적 락·IllegalStateException →
 * {@link ProductInvalidStateException}(422) 흡수·같은 트랜잭션 감사 로그)을 복제한다(관리자 무수정·셀러 서비스 관례). 소유권은
 * {@code findByPublicIdForUpdate} 후 {@code seller_id = 액터} 대조로 강제하며 타 셀러·미존재·삭제는 404로 은닉한다.
 *
 * <p><b>주체 가드</b>: 관리자 중지({@code saleStopSource=ADMIN}) 상품의 재판매는 {@link ProductStoppedByAdminException}(422
 * PRODUCT_STOPPED_BY_ADMIN)으로 거부한다 — 제재 우회 차단. 셀러 중지는 SELLER로 기록한다. 수동 품절은 제재가 아니므로 관리자가 켠
 * 품절도 셀러가 해제할 수 있다(D3 α). 감사는 actor_role=SELLER로 적재한다(D6 α·계좌 등록·송장 정정 선례).
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class SellerProductSaleStatusService {

    private final ProductRepository productRepository;
    private final AuditRecorder auditRecorder;

    /**
     * 판매 상태를 전환한다(SALE ↔ STOPPED). 같은 상태 재요청은 관리자와 같이 422(오조작 감지).
     *
     * @param sellerId     액터 셀러 id
     * @param publicId     대상 상품 public_id(prd_)
     * @param target       목표 상태(SALE·STOPPED만·DTO @Pattern으로 선검증됨)
     * @param auditContext 감사 행위자 컨텍스트(셀러 구성원)
     * @throws ProductNotFoundException       미존재·삭제·타 셀러 상품(404)
     * @throws ProductStoppedByAdminException 관리자 중지 상품 재판매(422)
     * @throws ProductInvalidStateException   허용 외 전이·같은 상태 재요청(422)
     */
    public void changeSaleStatus(Long sellerId, String publicId, ProductStatus target, AuditContext auditContext) {
        Product product = findOwnedForUpdate(sellerId, publicId);
        ProductStatus before = product.getStatus();
        Map<String, Object> beforeSnapshot = ProductSaleStatusService.saleStateSnapshot(product);
        if (target == ProductStatus.SALE && before == ProductStatus.STOPPED
                && product.getSaleStopSource() == SaleStopSource.ADMIN) {
            throw new ProductStoppedByAdminException(
                    "관리자가 판매중지한 상품은 셀러가 재판매할 수 없습니다(운영자 문의): publicId=" + publicId);
        }
        try {
            if (target == ProductStatus.STOPPED) {
                product.stopSale(SaleStopSource.SELLER);
            } else {
                product.resumeSale();
            }
        } catch (IllegalStateException exception) {
            throw new ProductInvalidStateException("판매 상태를 전환할 수 없습니다: " + exception.getMessage());
        }
        auditRecorder.record(auditContext, AuditLogAction.UPDATE, PolymorphicTargetType.PRODUCT, product.getId(),
                beforeSnapshot, ProductSaleStatusService.saleStateSnapshot(product));
        log.info("[SellerProduct] 판매 상태 전환({} → {}) sellerId={} publicId={}", before, product.getStatus(), sellerId, publicId);
    }

    /**
     * 상품 단위 수동 품절 on/off. 같은 값 재요청은 no-op(변경 없음이라 감사도 skip).
     *
     * @throws ProductNotFoundException 미존재·삭제·타 셀러 상품(404)
     */
    public void changeSoldOut(Long sellerId, String publicId, boolean soldOut, AuditContext auditContext) {
        Product product = findOwnedForUpdate(sellerId, publicId);
        boolean before = product.isSoldoutManual();
        product.changeSoldoutManual(soldOut);
        auditRecorder.record(auditContext, AuditLogAction.UPDATE, PolymorphicTargetType.PRODUCT, product.getId(),
                Map.of("soldoutManual", before), Map.of("soldoutManual", soldOut));
        log.info("[SellerProduct] 수동 품절 변경 sellerId={} publicId={} {} → {}", sellerId, publicId, before, soldOut);
    }

    /**
     * @throws ProductNotFoundException 미존재·삭제·타 셀러 상품(404·존재 은닉·SellerProductCommandService 동형)
     */
    private Product findOwnedForUpdate(Long sellerId, String publicId) {
        return productRepository.findByPublicIdForUpdate(publicId)
                .filter(candidate -> candidate.getSellerId().equals(sellerId))
                .orElseThrow(() -> new ProductNotFoundException("상품을 찾을 수 없습니다: publicId=" + publicId));
    }
}
