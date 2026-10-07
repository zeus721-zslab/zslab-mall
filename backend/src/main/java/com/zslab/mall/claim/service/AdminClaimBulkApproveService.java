package com.zslab.mall.claim.service;

import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.claim.controller.response.AdminClaimBulkApproveResponse;
import com.zslab.mall.claim.exception.ClaimInvalidStateException;
import com.zslab.mall.claim.exception.ClaimNotFoundException;
import com.zslab.mall.claim.exception.ClaimSuggestionMismatchException;
import com.zslab.mall.inventory.exception.InventoryInvariantViolationException;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * 관리자 클레임 일괄 승인(D-250). 항목마다 {@link ClaimService#approveSuggestedByAdmin}(클래스 트랜잭션)을 불러 항목별로 커밋한다 —
 * 한 항목의 실패가 앞 항목을 되돌리지 않는다(상품 일괄 {@code AdminProductBulkService} 선례 · 본 클래스는 의도적으로 {@code @Transactional} 없음).
 * 커밋마다 그 항목의 커밋 후 처리(취소 환불 개시·알림)가 요청 스레드에서 끝난 뒤 다음 항목으로 넘어간다.
 *
 * <p>항목 실패로 흡수하는 것은 상태·재고·버전 충돌·비관락 충돌·제안 불일치·미존재뿐이며 그 외 예외는 전파한다(예상 밖 오류 은닉 금지).
 */
@Slf4j
@Service
public class AdminClaimBulkApproveService {

    // GlobalExceptionHandler가 단건 API에 쓰는 코드와 같은 값 — 화면이 같은 문구 매핑을 쓴다.
    private static final String CODE_CLAIM_NOT_FOUND = "CLAIM_NOT_FOUND";
    private static final String CODE_CLAIM_STATE_INVALID = "CLAIM_STATE_INVALID";
    private static final String CODE_INVENTORY_INVARIANT_VIOLATION = "INVENTORY_INVARIANT_VIOLATION";
    private static final String CODE_OPTIMISTIC_LOCK_FAILURE = "OPTIMISTIC_LOCK_FAILURE";
    private static final String CODE_LOCK_CONFLICT = "LOCK_CONFLICT";
    /** 단건 API의 LOCK_CONFLICT 응답 문구와 같다. */
    private static final String LOCK_CONFLICT_MESSAGE = "다른 처리와 겹쳤습니다. 잠시 후 다시 시도해 주세요.";
    /** 일괄 승인 전용 — 지금 제안이 승인이 아니라 전이하지 않았다. */
    private static final String CODE_CLAIM_SUGGESTION_MISMATCH = "CLAIM_SUGGESTION_MISMATCH";

    private final ClaimService claimService;

    public AdminClaimBulkApproveService(ClaimService claimService) {
        this.claimService = claimService;
    }

    public AdminClaimBulkApproveResponse approve(List<String> claimPublicIds, AuditContext auditContext) {
        List<AdminClaimBulkApproveResponse.Item> results = new ArrayList<>();
        for (String claimPublicId : claimPublicIds) {
            results.add(approveOne(claimPublicId, auditContext));
        }
        AdminClaimBulkApproveResponse response = AdminClaimBulkApproveResponse.of(results);
        log.info("[AdminClaimBulk] 일괄 승인 requested={} success={} failure={}", claimPublicIds.size(),
                response.successCount(), response.failureCount());
        return response;
    }

    private AdminClaimBulkApproveResponse.Item approveOne(String claimPublicId, AuditContext auditContext) {
        try {
            claimService.approveSuggestedByAdmin(claimPublicId, LocalDateTime.now(), auditContext);
            return new AdminClaimBulkApproveResponse.Item(claimPublicId, true, null, null);
        } catch (ClaimNotFoundException exception) {
            return failure(claimPublicId, CODE_CLAIM_NOT_FOUND, exception);
        } catch (ClaimInvalidStateException exception) {
            return failure(claimPublicId, CODE_CLAIM_STATE_INVALID, exception);
        } catch (InventoryInvariantViolationException exception) {
            return failure(claimPublicId, CODE_INVENTORY_INVARIANT_VIOLATION, exception);
        } catch (OptimisticLockingFailureException exception) {
            return failure(claimPublicId, CODE_OPTIMISTIC_LOCK_FAILURE, exception);
        } catch (ClaimSuggestionMismatchException exception) {
            return failure(claimPublicId, CODE_CLAIM_SUGGESTION_MISMATCH, exception);
        } catch (PessimisticLockingFailureException | PessimisticLockException | LockTimeoutException exception) {
            // PF-05: GlobalExceptionHandler가 409 LOCK_CONFLICT로 받는 비관락 3종(D-268 OPS-05). 항목 실패로 흡수하지 않으면 앞 항목은
            // 커밋됐는데 응답 전체가 409가 돼 결과 목록이 사라진다. 예외 메시지에 SQL 원문이 섞여 응답에는 고정 문구를 싣는다.
            log.warn("[AdminClaimBulk] 항목 실패 claimPublicId={} code={}: {}", claimPublicId, CODE_LOCK_CONFLICT, exception.toString());
            return new AdminClaimBulkApproveResponse.Item(claimPublicId, false, CODE_LOCK_CONFLICT, LOCK_CONFLICT_MESSAGE);
        }
    }

    private static AdminClaimBulkApproveResponse.Item failure(String claimPublicId, String code, RuntimeException exception) {
        log.warn("[AdminClaimBulk] 항목 실패 claimPublicId={} code={}: {}", claimPublicId, code, exception.getMessage());
        return new AdminClaimBulkApproveResponse.Item(claimPublicId, false, code, exception.getMessage());
    }
}
