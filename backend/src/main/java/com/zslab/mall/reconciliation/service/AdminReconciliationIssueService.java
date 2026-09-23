package com.zslab.mall.reconciliation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zslab.mall.audit.enums.AuditLogAction;
import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.audit.service.AuditRecorder;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import com.zslab.mall.order.controller.response.PagedResponse;
import com.zslab.mall.order.entity.Order;
import com.zslab.mall.order.repository.OrderRepository;
import com.zslab.mall.reconciliation.controller.response.AdminReconciliationIssueResponse;
import com.zslab.mall.reconciliation.entity.ReconciliationIssue;
import com.zslab.mall.reconciliation.enums.ReconciliationIssueStatus;
import com.zslab.mall.reconciliation.enums.ReconciliationIssueType;
import com.zslab.mall.reconciliation.exception.ReconciliationIssueInvalidStateException;
import com.zslab.mall.reconciliation.exception.ReconciliationIssueNotFoundException;
import com.zslab.mall.reconciliation.repository.ReconciliationIssueRepository;
import com.zslab.mall.user.entity.User;
import com.zslab.mall.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 불일치 조회·해결(Track 104-2 D-216·invariants P6). 해결은 "확인·조치했다"는 기록일 뿐 업무 데이터를 보정하지 않는다 — 보정은
 * 기존 관리자 조작(수동 결제 취소·클레임 처리 등)으로 하고 여기엔 메모로 남긴다.
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AdminReconciliationIssueService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final String AUDIT_FIELD_STATUS = "status";
    private static final String AUDIT_FIELD_MEMO = "memo";

    private final ReconciliationIssueRepository reconciliationIssueRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final AuditRecorder auditRecorder;
    private final ObjectMapper objectMapper;

    /** 불일치 목록(최신순). 상태·유형은 null이면 전체. */
    public PagedResponse<AdminReconciliationIssueResponse> list(
            ReconciliationIssueStatus status, ReconciliationIssueType issueType, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), clampSize(size), Sort.by(Sort.Direction.DESC, "id"));
        Specification<ReconciliationIssue> specification = Specification
                .where(statusEquals(status))
                .and(issueTypeEquals(issueType));
        Page<ReconciliationIssue> issues = reconciliationIssueRepository.findAll(specification, pageable);
        return PagedResponse.from(new PageImpl<>(toResponses(issues.getContent()), pageable, issues.getTotalElements()));
    }

    /** 주문 상세 불일치 섹션(해결 포함·최신순). */
    public List<AdminReconciliationIssueResponse> listByOrder(Long orderId) {
        return toResponses(reconciliationIssueRepository.findByOrderIdOrderByIdDesc(orderId));
    }

    /**
     * 불일치를 해결 처리하고 감사(UPDATE·RECONCILIATION_ISSUE·status OPEN→RESOLVED + memo)를 남긴다.
     *
     * @throws ReconciliationIssueNotFoundException      불일치 미존재(404)
     * @throws ReconciliationIssueInvalidStateException 이미 해결됨(422)
     */
    @Transactional
    public AdminReconciliationIssueResponse resolve(long issueId, String memo, AuditContext auditContext) {
        ReconciliationIssue issue = reconciliationIssueRepository.findByIdForUpdate(issueId)
                .orElseThrow(() -> new ReconciliationIssueNotFoundException("불일치를 찾을 수 없습니다: id=" + issueId));
        ReconciliationIssueStatus before = issue.getStatus();
        issue.resolve(auditContext.actorUserId(), memo, LocalDateTime.now());
        auditRecorder.record(auditContext, AuditLogAction.UPDATE, PolymorphicTargetType.RECONCILIATION_ISSUE, issue.getId(),
                Map.of(AUDIT_FIELD_STATUS, before.name()),
                Map.of(AUDIT_FIELD_STATUS, issue.getStatus().name(), AUDIT_FIELD_MEMO, memo));
        return toResponses(List.of(issue)).get(0);
    }

    /** 주문(번호 표시·링크)·해결자 이름을 각 1쿼리로 모아 응답을 만든다(N+1 회피). */
    private List<AdminReconciliationIssueResponse> toResponses(List<ReconciliationIssue> issues) {
        List<Long> orderIds = issues.stream().map(ReconciliationIssue::getOrderId).filter(Objects::nonNull).distinct().toList();
        Map<Long, Order> orders = orderIds.isEmpty() ? Map.of()
                : orderRepository.findAllById(orderIds).stream().collect(Collectors.toMap(Order::getId, Function.identity()));
        List<Long> resolverIds = issues.stream().map(ReconciliationIssue::getResolvedBy).filter(Objects::nonNull).distinct().toList();
        Map<Long, User> resolvers = resolverIds.isEmpty() ? Map.of()
                : userRepository.findByIdIn(resolverIds).stream().collect(Collectors.toMap(User::getId, Function.identity()));
        return issues.stream()
                .map(issue -> {
                    Order order = issue.getOrderId() == null ? null : orders.get(issue.getOrderId());
                    User resolver = issue.getResolvedBy() == null ? null : resolvers.get(issue.getResolvedBy());
                    return new AdminReconciliationIssueResponse(issue.getId(), issue.getIssueType().name(), issue.getStatus().name(),
                            order == null ? null : order.getPublicId(), order == null ? null : order.getOrderNo(),
                            issue.getPgTid(), issue.getPgRefundId(), parseDetail(issue), issue.getDetectedAt(),
                            issue.getResolvedAt(), resolver == null ? null : resolver.getName(),
                            issue.getStatus() == ReconciliationIssueStatus.RESOLVED && issue.getResolvedBy() == null,
                            issue.getResolutionMemo());
                })
                .toList();
    }

    private Map<String, Object> parseDetail(ReconciliationIssue issue) {
        if (issue.getDetail() == null) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(issue.getDetail(), new TypeReference<Map<String, Object>>() { });
        } catch (JsonProcessingException exception) {
            // DDL CHECK(JSON_VALID)로 도달 경로가 없다. 한 행 때문에 목록 전체가 깨지지 않도록 세부만 비우고 남긴다.
            log.warn("[Reconciliation] 불일치 세부 파싱 실패 id={}", issue.getId(), exception);
            return Map.of();
        }
    }

    private static Specification<ReconciliationIssue> statusEquals(ReconciliationIssueStatus status) {
        return (root, query, builder) -> status == null ? null : builder.equal(root.get("status"), status);
    }

    private static Specification<ReconciliationIssue> issueTypeEquals(ReconciliationIssueType issueType) {
        return (root, query, builder) -> issueType == null ? null : builder.equal(root.get("issueType"), issueType);
    }

    private static int clampSize(int size) {
        if (size < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
