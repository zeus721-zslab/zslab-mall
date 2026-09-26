package com.zslab.mall.seller.service;

import com.zslab.mall.audit.enums.AuditLogAction;
import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.audit.service.AuditRecorder;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import com.zslab.mall.common.exception.MalformedRequestException;
import com.zslab.mall.common.security.DemoAccountGuard;
import com.zslab.mall.seller.controller.request.AdminSellerUpdateRequest;
import com.zslab.mall.seller.controller.response.AdminSellerDetailResponse;
import com.zslab.mall.seller.entity.Seller;
import com.zslab.mall.seller.entity.SellerUser;
import com.zslab.mall.seller.entity.WithdrawnSeller;
import com.zslab.mall.seller.enums.SellerStatus;
import com.zslab.mall.seller.exception.SellerBusinessNoDuplicateException;
import com.zslab.mall.seller.exception.SellerInvalidStateException;
import com.zslab.mall.seller.exception.SellerNotFoundException;
import com.zslab.mall.seller.repository.SellerRepository;
import com.zslab.mall.seller.repository.SellerUserRepository;
import com.zslab.mall.seller.repository.WithdrawnSellerRepository;
import com.zslab.mall.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 셀러 명령(Track 89-D·D-187): 상태 전이·정보 수정. 둘 다 {@code findByPublicIdForUpdate}(비관적 락)로 같은 셀러의 동시 명령을
 * 직렬화하고 같은 트랜잭션에서 감사(UPDATE·SELLER)를 적재한다.
 *
 * <p><b>상태 전이</b>: 전이 합법성(422) → TERMINATED면 종료 가드(409·{@link SellerTerminationGuard}) → {@code Seller.changeStatus} →
 * 감사(before/after status + reason) → TERMINATED면 {@link WithdrawnSeller} INSERT(SLR-6·같은 TX·롤백 시 함께 롤백).
 * 상품 상태는 바꾸지 않는다 — 카탈로그 목록·상세 쿼리가 셀러 ACTIVE를 조건으로 가져 노출은 자동 차단된다(D-187).
 */
@Slf4j
@Service
@Transactional
public class AdminSellerCommandService {

    /** 종료 판매자 법정 보관 기간(전자상거래법 거래기록 5년·deletion-policy §3). 만료 후 비식별화 배치는 D-23 후속(이월). */
    private static final int LEGAL_RETENTION_YEARS = 5;

    private final SellerRepository sellerRepository;
    private final WithdrawnSellerRepository withdrawnSellerRepository;
    private final SellerTerminationGuard sellerTerminationGuard;
    private final AdminSellerQueryService adminSellerQueryService;
    private final AuditRecorder auditRecorder;
    private final SellerUserRepository sellerUserRepository;
    private final UserRepository userRepository;
    private final DemoAccountGuard demoAccountGuard;

    public AdminSellerCommandService(
            SellerRepository sellerRepository,
            WithdrawnSellerRepository withdrawnSellerRepository,
            SellerTerminationGuard sellerTerminationGuard,
            AdminSellerQueryService adminSellerQueryService,
            AuditRecorder auditRecorder,
            SellerUserRepository sellerUserRepository,
            UserRepository userRepository,
            DemoAccountGuard demoAccountGuard) {
        this.sellerRepository = sellerRepository;
        this.withdrawnSellerRepository = withdrawnSellerRepository;
        this.sellerTerminationGuard = sellerTerminationGuard;
        this.adminSellerQueryService = adminSellerQueryService;
        this.auditRecorder = auditRecorder;
        this.sellerUserRepository = sellerUserRepository;
        this.userRepository = userRepository;
        this.demoAccountGuard = demoAccountGuard;
    }

    /**
     * 셀러 상태 전이.
     *
     * @param sellerPublicId 대상 셀러 public_id(slr_)
     * @param target         목표 상태(ACTIVE·SUSPENDED·TERMINATED·DTO @Pattern 선검증)
     * @param reason         사유(감사·종료 아카이브)
     * @return 전이 후 상세(종료 가능 여부 등 갱신 반영)
     *
     * <p><b>락 범위(D-187 외부 검토 지적 1 확정)</b>: {@code findByPublicIdForUpdate}의 비관적 락은 <b>같은 셀러에 대한 관리자 명령(전이·수정)의
     * 직렬화</b>까지만 보장한다(둘째 요청은 락 해제 후 최신 상태로 합법성을 재판정 → 같은 상태 재요청은 422). 종료 가드 통과 후 커밋 전에 유입되는
     * 주문·클레임·정산 생성은 이 락으로 막지 않는다(그 경로들은 셀러 행 락을 잡지 않음·{@link SellerTerminationGuard} 판정 시점 참조).
     *
     * @throws SellerNotFoundException 미존재(404)
     * @throws SellerInvalidStateException 불법 전이·같은 상태 재요청(422)
     * @throws com.zslab.mall.seller.exception.SellerActivityInProgressException 종료 가드 위반(409)
     * @throws com.zslab.mall.common.exception.DemoAccountProtectedException 데모 계정이 소속된 셀러 해지(403)
     */
    public AdminSellerDetailResponse changeStatus(
            String sellerPublicId, SellerStatus target, String reason, AuditContext auditContext) {
        Seller seller = sellerRepository.findByPublicIdForUpdate(sellerPublicId)
                .orElseThrow(() -> new SellerNotFoundException("셀러를 찾을 수 없습니다: " + sellerPublicId));
        SellerStatus before = seller.getStatus();
        // 합법성(422)을 가드(409)보다 먼저 본다 — TERMINATED 셀러에 다시 TERMINATED를 요청하면 가드가 아니라 전이 위반이다.
        if (!before.canTransitionTo(target)) {
            throw new SellerInvalidStateException(
                    "판매자 상태를 전환할 수 없습니다: " + before + " → " + target + " sellerPublicId=" + sellerPublicId);
        }
        if (target == SellerStatus.TERMINATED) {
            // D-230: 해지는 되돌릴 수 없어 데모 셀러 로그인이 영구히 막힌다 → 보호 계정이 소속된 셀러는 해지 차단(정지는 허용).
            demoAccountGuard.requireNoProtectedMember(userRepository.findByIdIn(
                    sellerUserRepository.findBySellerId(seller.getId()).stream().map(SellerUser::getUserId).toList()));
            sellerTerminationGuard.requireTerminable(seller.getId());
        }
        String trimmedReason = reason.trim();
        seller.changeStatus(target);

        auditRecorder.record(auditContext, AuditLogAction.UPDATE, PolymorphicTargetType.SELLER, seller.getId(),
                Map.of("status", before.name()),
                Map.of("status", target.name(), "reason", trimmedReason));
        if (target == SellerStatus.TERMINATED) {
            LocalDateTime now = LocalDateTime.now();
            withdrawnSellerRepository.save(
                    WithdrawnSeller.create(seller, trimmedReason, now.plusYears(LEGAL_RETENTION_YEARS)));
        }
        log.info("[AdminSeller] 상태 전이 {} → {} sellerPublicId={} byActor={}",
                before, target, sellerPublicId, auditContext.actorUserId());
        return adminSellerQueryService.toDetail(seller);
    }

    /**
     * 셀러 정보 수정(상호·사업자번호·대표자·연락처·수수료율). 수수료율이 실제로 바뀔 때만 사유가 필수이며(89-C 카테고리 선례) 값이 하나도
     * 안 바뀌면 감사 없이 no-op다. 수수료율은 주문 생성 시점에 order_item에 스냅샷되므로 변경은 이후 체크아웃되는 신규 주문에만 적용되고
     * 기존 주문·생성된 정산·PENDING 재생성에는 영향이 없다(D-179 결정 2).
     *
     * @throws SellerNotFoundException 미존재(404)
     * @throws MalformedRequestException 수수료율이 바뀌는데 사유가 공백일 때(400)
     * @throws SellerBusinessNoDuplicateException 사업자번호가 다른 셀러와 중복(409)
     */
    public void update(String sellerPublicId, AdminSellerUpdateRequest request, AuditContext auditContext) {
        Seller seller = sellerRepository.findByPublicIdForUpdate(sellerPublicId)
                .orElseThrow(() -> new SellerNotFoundException("셀러를 찾을 수 없습니다: " + sellerPublicId));
        String companyName = request.companyName().trim();
        String ceoName = request.ceoName().trim();
        String businessNo = blankToNull(request.businessNo());
        String contactEmail = blankToNull(request.contactEmail());
        String contactPhone = blankToNull(request.contactPhone());
        boolean commissionRateChanged = !Objects.equals(seller.getCommissionRate(), request.commissionRate());
        String reason = request.reason() == null ? "" : request.reason().trim();
        if (commissionRateChanged && reason.isEmpty()) {
            throw new MalformedRequestException("수수료율 변경 시 사유는 필수입니다: sellerPublicId=" + sellerPublicId);
        }
        boolean changed = commissionRateChanged
                || !companyName.equals(seller.getCompanyName())
                || !Objects.equals(businessNo, seller.getBusinessNo())
                || !ceoName.equals(seller.getCeoName())
                || !Objects.equals(contactEmail, seller.getContactEmail())
                || !Objects.equals(contactPhone, seller.getContactPhone());
        if (!changed) {
            log.info("[AdminSeller] 수정 요청 값 무변경 → 감사 skip sellerPublicId={}", sellerPublicId);
            return;
        }
        if (businessNo != null && !businessNo.equals(seller.getBusinessNo())
                && sellerRepository.existsByBusinessNo(businessNo)) {
            throw new SellerBusinessNoDuplicateException("이미 등록된 사업자번호입니다: " + businessNo);
        }
        Map<String, Object> before = snapshot(seller);
        seller.update(companyName, businessNo, ceoName, contactEmail, contactPhone, request.commissionRate());
        try {
            // 선검사 통과 후 동시 등록 레이스는 uk_seller_business_no flush 위반으로 잡아 같은 409로 변환한다.
            sellerRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            // 외부 검토 지적 2: 사업자번호 UK 위반만 409로 변환하고 그 외 무결성 오류는 원래 예외 경로(500)로 보낸다.
            if (!SellerConstraintViolations.isBusinessNoDuplicate(exception)) {
                throw exception;
            }
            log.warn("[AdminSeller] 사업자번호 중복 차단(409·uk_seller_business_no) businessNo={}: {}",
                    businessNo, exception.getMostSpecificCause().getMessage());
            throw new SellerBusinessNoDuplicateException("이미 등록된 사업자번호입니다: " + businessNo);
        }
        Map<String, Object> after = snapshot(seller);
        if (!reason.isEmpty()) {
            after.put("reason", reason);
        }
        auditRecorder.record(auditContext, AuditLogAction.UPDATE, PolymorphicTargetType.SELLER, seller.getId(),
                before, after);
        log.info("[AdminSeller] 수정 sellerPublicId={} commissionRateChanged={} byActor={}",
                sellerPublicId, commissionRateChanged, auditContext.actorUserId());
    }

    /** 감사 스냅샷(LinkedHashMap·null 허용 — Map.of는 null 값을 거부한다). */
    private static Map<String, Object> snapshot(Seller seller) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("companyName", seller.getCompanyName());
        fields.put("businessNo", seller.getBusinessNo());
        fields.put("ceoName", seller.getCeoName());
        fields.put("contactEmail", seller.getContactEmail());
        fields.put("contactPhone", seller.getContactPhone());
        fields.put("commissionRate", seller.getCommissionRate());
        return fields;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
