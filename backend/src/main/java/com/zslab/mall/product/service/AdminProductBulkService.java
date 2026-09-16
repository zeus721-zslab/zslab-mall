package com.zslab.mall.product.service;

import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.product.controller.response.AdminProductBulkResponse;
import com.zslab.mall.product.enums.ProductStatus;
import com.zslab.mall.product.exception.ProductInvalidStateException;
import com.zslab.mall.product.exception.ProductNotFoundException;
import com.zslab.mall.product.repository.ProductRepository;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 관리자 일괄 변경 서비스(Track 76). 항목별로 기존 단건 서비스(승인·판매상태·수동품절)를 호출하며, 각 호출이 독립 트랜잭션이라
 * 한 항목의 실패가 다른 항목을 롤백하지 않는다(부분 성공·본 클래스는 의도적으로 {@code @Transactional} 없음). 실패는 예외를 삼키지
 * 않고 항목 결과(code·message)로 변환해 반환한다 — 운영자가 표에서 실패 행만 확인·재시도한다.
 *
 * <p>상태 변경 허용 전이 = PENDING→SALE(승인 경로 재사용)·SALE→STOPPED·STOPPED→SALE. 그 외(REJECTED·HIDDEN·SALE→PENDING 등)는
 * 단건 서비스가 422로 거부하며 PRODUCT_INVALID_STATE로 보고된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminProductBulkService {

    private static final String CODE_PRODUCT_NOT_FOUND = "PRODUCT_NOT_FOUND";
    private static final String CODE_PRODUCT_INVALID_STATE = "PRODUCT_INVALID_STATE";

    private final ProductRepository productRepository;
    private final ProductApprovalService productApprovalService;
    private final ProductSaleStatusService productSaleStatusService;
    private final AdminProductCommandService adminProductCommandService;

    /** 일괄 상태 변경(SALE·STOPPED). PENDING 상품에 SALE을 요청하면 승인 전이로 처리한다. */
    public AdminProductBulkResponse changeStatus(List<String> productPublicIds, ProductStatus target, AuditContext auditContext) {
        List<AdminProductBulkResponse.Item> results = new ArrayList<>();
        for (String publicId : productPublicIds) {
            results.add(execute(publicId, () -> {
                boolean pendingToSale = target == ProductStatus.SALE
                        && productRepository.findByPublicId(publicId)
                                .map(product -> product.getStatus() == ProductStatus.PENDING)
                                .orElse(false);
                if (pendingToSale) {
                    productApprovalService.approve(publicId, auditContext);
                } else {
                    productSaleStatusService.changeSaleStatus(publicId, target, auditContext);
                }
            }));
        }
        log.info("[AdminProductBulk] 상태 일괄 변경 target={} requested={}", target, productPublicIds.size());
        return AdminProductBulkResponse.of(results);
    }

    /** 일괄 수동 품절 on/off. */
    public AdminProductBulkResponse changeSoldOut(List<String> productPublicIds, boolean soldOut, AuditContext auditContext) {
        List<AdminProductBulkResponse.Item> results = new ArrayList<>();
        for (String publicId : productPublicIds) {
            results.add(execute(publicId, () -> adminProductCommandService.changeSoldOut(publicId, soldOut, auditContext)));
        }
        log.info("[AdminProductBulk] 수동 품절 일괄 변경 soldOut={} requested={}", soldOut, productPublicIds.size());
        return AdminProductBulkResponse.of(results);
    }

    /** 단건 실행을 결과 항목으로 변환한다. 도메인 예외 2종만 항목 실패로 흡수하고, 그 외 예외는 그대로 전파한다(예상 밖 오류 은닉 금지). */
    private AdminProductBulkResponse.Item execute(String publicId, Runnable action) {
        try {
            action.run();
            return new AdminProductBulkResponse.Item(publicId, true, null, null);
        } catch (ProductNotFoundException exception) {
            log.warn("[AdminProductBulk] 항목 실패(미존재) publicId={}: {}", publicId, exception.getMessage());
            return new AdminProductBulkResponse.Item(publicId, false, CODE_PRODUCT_NOT_FOUND, exception.getMessage());
        } catch (ProductInvalidStateException exception) {
            log.warn("[AdminProductBulk] 항목 실패(전이 불가) publicId={}: {}", publicId, exception.getMessage());
            return new AdminProductBulkResponse.Item(publicId, false, CODE_PRODUCT_INVALID_STATE, exception.getMessage());
        }
    }
}
