package com.zslab.mall.audit.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zslab.mall.audit.controller.response.AdminAuditLogResponse;
import com.zslab.mall.audit.entity.AuditLog;
import com.zslab.mall.audit.repository.AuditLogRepository;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import com.zslab.mall.delivery.entity.Delivery;
import com.zslab.mall.delivery.repository.DeliveryRepository;
import com.zslab.mall.order.controller.response.PagedResponse;
import com.zslab.mall.user.entity.User;
import com.zslab.mall.user.repository.UserRepository;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 대상별 처리 이력 조회(Track 101-A). 불가역 조작에 감사 행을 남기기 시작하면서(STEP 6) 그 행을 운영 화면에서
 * 읽을 경로가 필요해졌다 — 그전까지 audit_log는 적재 전용이고 조회 endpoint가 하나도 없었다(정찰 라운드 3 §2-4).
 *
 * <p><b>범위</b>: 대상 1건(targetType + targetId)의 이력만 돌려준다. 전체 감사 로그를 훑는 조회는 만들지 않는다 —
 * 소비처가 클레임 상세·정산 상세 두 화면뿐이고, 무제한 조회는 그 자체로 감사 데이터 유출 면이 된다(기조 4).
 * 클레임만 예외로 연결 배송 행을 합친다({@link #listByClaim}·Track 103).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAuditLogQueryService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;
    private final DeliveryRepository deliveryRepository;
    private final ObjectMapper objectMapper;

    /**
     * 대상 1건의 처리 이력을 최신순으로 조회한다. page는 음수 보정, size는 1~100으로 클램프한다(다른 관리자 목록과 같은 규약).
     *
     * @param targetType 대상 유형
     * @param targetId   대상 id(논리참조)
     * @param page       0부터
     * @param size       1~100
     */
    @Transactional(readOnly = true)
    public PagedResponse<AdminAuditLogResponse> listByTarget(
            PolymorphicTargetType targetType, Long targetId, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), clampSize(size));
        Page<AuditLog> logs = auditLogRepository.findByTargetTypeAndTargetIdOrderByIdDesc(targetType, targetId, pageable);
        return toPagedResponse(logs);
    }

    /**
     * 클레임 처리 이력을 최신순으로 조회한다(Track 103 D-214). 클레임 행에 더해 그 클레임에 연결된 배송(claim_id — 회수·교환품·재발송)의
     * 감사 행(대행 송장 등록·교환품 발송·송장 정정·자동 배송완료)을 합친다 — 운영자가 한 클레임의 처리 흐름을 한 목록에서 보게 하려는 것.
     * 연결 배송 id는 1쿼리, 이력은 1쿼리(+count)라 N+1이 없다. page·size 규약은 {@link #listByTarget}과 같다.
     *
     * @param claimId 클레임 id
     * @param page    0부터
     * @param size    1~100
     */
    @Transactional(readOnly = true)
    public PagedResponse<AdminAuditLogResponse> listByClaim(Long claimId, int page, int size) {
        List<Long> deliveryIds = deliveryRepository.findByClaimIdInOrderByIdDesc(List.of(claimId)).stream()
                .map(Delivery::getId)
                .toList();
        if (deliveryIds.isEmpty()) {
            return listByTarget(PolymorphicTargetType.CLAIM, claimId, page, size);
        }
        Pageable pageable = PageRequest.of(Math.max(page, 0), clampSize(size));
        Page<AuditLog> logs = auditLogRepository.findClaimHistory(
                PolymorphicTargetType.CLAIM, claimId, PolymorphicTargetType.DELIVERY, deliveryIds, pageable);
        return toPagedResponse(logs);
    }

    private PagedResponse<AdminAuditLogResponse> toPagedResponse(Page<AuditLog> logs) {
        Map<Long, User> actors = actorsById(logs.getContent());
        return PagedResponse.from(logs.map(log -> {
            User actor = log.getActorUserId() == null ? null : actors.get(log.getActorUserId());
            return AdminAuditLogResponse.of(log,
                    actor == null ? null : actor.getName(),
                    actor == null ? null : actor.getEmail(),
                    parseChanges(log));
        }));
    }

    /**
     * 페이지에 등장한 행위자를 1쿼리로 모아 온다(N+1 회피). actor_user_id는 논리참조라 회원 행이 이미 지워졌을 수 있고,
     * 스케줄러 행({@code AuditContext.system()})은 아예 null이다 — 둘 다 맵에 없어 화면은 역할만 보여 준다.
     */
    private Map<Long, User> actorsById(List<AuditLog> logs) {
        List<Long> actorIds = logs.stream()
                .map(AuditLog::getActorUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (actorIds.isEmpty()) {
            return Map.of();
        }
        return userRepository.findByIdIn(actorIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
    }

    private int clampSize(int size) {
        if (size < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    /**
     * diff_json({@code {"field":{"before":..,"after":..}}})을 화면용 변경 목록으로 편다.
     *
     * <p>파싱 실패는 삼키고 빈 목록으로 돌린다 — 과거에 적재된 행 하나가 깨져 있다고 이력 화면 전체가 500이 되면
     * 오히려 추적이 막힌다. 대신 어느 행인지 경고 로그로 남긴다.
     */
    private List<AdminAuditLogResponse.Change> parseChanges(AuditLog auditLog) {
        String diffJson = auditLog.getDiffJson();
        if (diffJson == null || diffJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(diffJson);
            List<AdminAuditLogResponse.Change> changes = new ArrayList<>();
            for (Iterator<Map.Entry<String, JsonNode>> fields = root.fields(); fields.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = fields.next();
                JsonNode change = entry.getValue();
                changes.add(new AdminAuditLogResponse.Change(
                        entry.getKey(), asText(change.get("before")), asText(change.get("after"))));
            }
            return changes;
        } catch (JsonProcessingException exception) {
            log.warn("[Audit] diff_json 파싱 실패 → 변경 요약 생략: auditPublicId={}", auditLog.getPublicId(), exception);
            return List.of();
        }
    }

    /** JSON 값 → 표시 문자열. null·JSON null은 "값 없음"을 뜻하는 null로 되돌린다. */
    private String asText(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.isTextual() ? node.textValue() : node.toString();
    }
}
