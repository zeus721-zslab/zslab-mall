package com.zslab.mall.seller.service;

import com.zslab.mall.audit.enums.AuditLogAction;
import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.audit.service.AuditRecorder;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import com.zslab.mall.seller.controller.request.AdminSellerBankAccountRegisterRequest;
import com.zslab.mall.seller.controller.request.AdminSellerBankAccountUpdateRequest;
import com.zslab.mall.seller.controller.response.AdminSellerBankAccountResponse;
import com.zslab.mall.seller.entity.Seller;
import com.zslab.mall.seller.entity.SellerBankAccount;
import com.zslab.mall.seller.exception.SellerBankAccountInvalidStateException;
import com.zslab.mall.seller.exception.SellerBankAccountNotFoundException;
import com.zslab.mall.seller.exception.SellerBankAccountReferencedException;
import com.zslab.mall.seller.exception.SellerNotFoundException;
import com.zslab.mall.seller.repository.SellerBankAccountRepository;
import com.zslab.mall.seller.repository.SellerRepository;
import com.zslab.mall.settlement.repository.SettlementRepository;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 셀러 정산계좌 명령(Track 89-F·D-188): 등록·수정·주 계좌 전환. 세 명령 모두 {@code findByPublicIdForUpdate}(셀러 행 비관적 락)로
 * 같은 셀러에 대한 동시 명령을 직렬화한다 — 특히 "첫 계좌 자동 주 계좌"·"demote → promote"가 두 요청에 겹치면 SLR-3이 깨지므로
 * DB UNIQUE({@code uk_seller_bank_account_primary}·V32)가 최종 방어선이고 이 락이 정상 경로다.
 *
 * <p>계좌번호는 엔티티 Converter가 암호화하므로 서비스는 평문만 다룬다. 감사 diff에는 {@code accountNumber} 키로 실어 {@code Masker}가
 * 자동 마스킹하고(감사 로그에 계좌 실값 없음), 사람이 읽을 끝 4자리는 {@code accountNumberSuffix}로 병기한다. 로그에도 실값을 남기지 않는다.
 *
 * <p>서비스 시그니처는 액터 무관(sellerId 기준)이라 Track 90 셀러 본인 등록이 같은 서비스를 셀러 컨트롤러에서 재사용할 수 있다(D-188 §8).
 */
@Service
@Transactional
public class AdminSellerBankAccountCommandService {

    private static final Logger log = LoggerFactory.getLogger(AdminSellerBankAccountCommandService.class);

    private final SellerRepository sellerRepository;
    private final SellerBankAccountRepository sellerBankAccountRepository;
    private final SettlementRepository settlementRepository;
    private final AuditRecorder auditRecorder;

    public AdminSellerBankAccountCommandService(
            SellerRepository sellerRepository,
            SellerBankAccountRepository sellerBankAccountRepository,
            SettlementRepository settlementRepository,
            AuditRecorder auditRecorder) {
        this.sellerRepository = sellerRepository;
        this.sellerBankAccountRepository = sellerBankAccountRepository;
        this.settlementRepository = settlementRepository;
        this.auditRecorder = auditRecorder;
    }

    /**
     * 계좌 등록. 셀러의 첫 계좌면 자동으로 주 계좌가 되고(지급 가능 상태로 바로 진입), 이미 계좌가 있으면 비주계좌로 추가된다(전환은 별도 명령).
     * status는 VERIFIED·verifiedAt=now — 운영자가 셀러와 확인한 계좌를 넣는 경로이며 실명인증 연동은 이월(D-188 §8).
     * 최초 등록은 사유 없이 감사 CREATE만 남긴다.
     *
     * @throws SellerNotFoundException 셀러 미존재(404)
     */
    public AdminSellerBankAccountResponse register(
            String sellerPublicId, AdminSellerBankAccountRegisterRequest request, AuditContext auditContext) {
        Seller seller = requireSellerForUpdate(sellerPublicId);
        boolean first = !sellerBankAccountRepository.existsBySellerId(seller.getId());
        SellerBankAccount account = SellerBankAccount.create(
                seller, request.bankCode().trim(), request.accountNumber().trim(), request.accountHolder().trim(), first);
        account.markVerified(LocalDateTime.now());
        sellerBankAccountRepository.saveAndFlush(account);

        auditRecorder.record(auditContext, AuditLogAction.CREATE, PolymorphicTargetType.SETTLEMENT_BANK_ACCOUNT, account.getId(),
                Map.of(), snapshot(account));
        log.info("[AdminSellerBankAccount] 등록 sellerPublicId={} bankAccountId={} primary={} byActor={}",
                sellerPublicId, account.getId(), first, auditContext.actorUserId());
        // 방금 INSERT한 행이라 참조 정산이 없다. 미리보기와 같은 기준(existsByBankAccountId)으로 판정해 응답 필드 의미를 통일한다.
        return AdminSellerBankAccountResponse.of(account, settlementRepository.existsByBankAccountId(account.getId()));
    }

    /**
     * 계좌 정보 in-place 수정(은행·계좌번호·예금주). 사유 필수(DTO). 값이 하나도 안 바뀌면 감사 없이 no-op.
     *
     * <p><b>정산 참조 행 판정 = {@code settlement.bank_account_id = 이 계좌 id}인 정산이 1건이라도 존재</b>(상태 무관·
     * {@code SettlementRepository.existsByBankAccountId}). bank_account_id는 {@code Settlement.markPaid}에서만 설정되므로 실질적으로
     * "PAID 정산이 지급 계좌로 스냅샷한 행"이다. 그 행을 고치면 정산 상세가 {@code findById(bankAccountId)}로 현재 값을 보여줘 지급 이력이
     * 변조되므로 409로 막고, 운영자는 새 계좌를 등록한 뒤 주 계좌를 전환한다. PENDING·CONFIRMED 정산은 bank_account_id가 NULL이라
     * 참조하지 않으며 지급 시점에 주 계좌를 재조회하므로 수정이 그대로 반영된다(D-179 결정 7).
     *
     * @throws SellerNotFoundException 셀러 미존재(404)
     * @throws SellerBankAccountNotFoundException 계좌 미존재·타 셀러 소속(404)
     * @throws SellerBankAccountReferencedException 정산 참조 행(409)
     */
    public void update(String sellerPublicId, Long bankAccountId, AdminSellerBankAccountUpdateRequest request,
            AuditContext auditContext) {
        Seller seller = requireSellerForUpdate(sellerPublicId);
        SellerBankAccount account = requireAccount(seller, bankAccountId);
        if (settlementRepository.existsByBankAccountId(account.getId())) {
            throw new SellerBankAccountReferencedException(
                    "정산이 지급 계좌로 참조하는 계좌는 수정할 수 없습니다(새 계좌 등록 후 주 계좌 전환): bankAccountId=" + bankAccountId);
        }
        String bankCode = request.bankCode().trim();
        String accountNumber = request.accountNumber().trim();
        String accountHolder = request.accountHolder().trim();
        boolean changed = !bankCode.equals(account.getBankCode())
                || !accountNumber.equals(account.getAccountNumber())
                || !accountHolder.equals(account.getAccountHolder());
        if (!changed) {
            log.info("[AdminSellerBankAccount] 수정 요청 값 무변경 → 감사 skip bankAccountId={}", bankAccountId);
            return;
        }
        Map<String, Object> before = snapshot(account);
        account.update(bankCode, accountNumber, accountHolder);
        account.markVerified(LocalDateTime.now());
        Map<String, Object> after = snapshot(account);
        after.put("reason", request.reason().trim());
        auditRecorder.record(auditContext, AuditLogAction.UPDATE, PolymorphicTargetType.SETTLEMENT_BANK_ACCOUNT, account.getId(),
                before, after);
        log.info("[AdminSellerBankAccount] 수정 sellerPublicId={} bankAccountId={} byActor={}",
                sellerPublicId, bankAccountId, auditContext.actorUserId());
    }

    /**
     * 주 계좌 전환. 순서가 핵심이다: (1) 벌크 JPQL로 셀러의 기존 주 계좌를 전부 해제(즉시 실행) → (2) 대상 엔티티 {@code markPrimary()}
     * (커밋 시 flush). 두 엔티티를 함께 바꾸면 Hibernate flush 순서가 보장되지 않아 promote가 먼저 나가는 순간
     * {@code uk_seller_bank_account_primary} 위반이 난다. 이미 주 계좌면 422(같은 상태 재요청 관습). 사유 필수·감사 UPDATE.
     *
     * @throws SellerNotFoundException 셀러 미존재(404)
     * @throws SellerBankAccountNotFoundException 계좌 미존재·타 셀러 소속(404)
     * @throws SellerBankAccountInvalidStateException 이미 주 계좌(422)
     */
    public void changePrimary(String sellerPublicId, Long bankAccountId, String reason, AuditContext auditContext) {
        Seller seller = requireSellerForUpdate(sellerPublicId);
        SellerBankAccount account = requireAccount(seller, bankAccountId);
        if (account.isPrimary()) {
            throw new SellerBankAccountInvalidStateException("이미 주 정산계좌입니다: bankAccountId=" + bankAccountId);
        }
        List<Long> previousPrimaryIds = sellerBankAccountRepository.findPrimaryBankAccountIds(seller.getId());
        int demoted = sellerBankAccountRepository.demotePrimary(seller.getId(), LocalDateTime.now());
        account.markPrimary();
        sellerBankAccountRepository.flush();

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("isPrimary", false);
        before.put("previousPrimaryBankAccountIds", previousPrimaryIds);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("isPrimary", true);
        after.put("accountNumberSuffix", account.accountNumberSuffix());
        after.put("reason", reason.trim());
        auditRecorder.record(auditContext, AuditLogAction.UPDATE, PolymorphicTargetType.SETTLEMENT_BANK_ACCOUNT, account.getId(),
                before, after);
        log.info("[AdminSellerBankAccount] 주 계좌 전환 sellerPublicId={} bankAccountId={} demoted={} byActor={}",
                sellerPublicId, bankAccountId, demoted, auditContext.actorUserId());
    }

    private Seller requireSellerForUpdate(String sellerPublicId) {
        return sellerRepository.findByPublicIdForUpdate(sellerPublicId)
                .orElseThrow(() -> new SellerNotFoundException("셀러를 찾을 수 없습니다: " + sellerPublicId));
    }

    private SellerBankAccount requireAccount(Seller seller, Long bankAccountId) {
        return sellerBankAccountRepository.findByIdAndSellerId(bankAccountId, seller.getId())
                .orElseThrow(() -> new SellerBankAccountNotFoundException(
                        "정산계좌를 찾을 수 없습니다: sellerPublicId=" + seller.getPublicId() + " bankAccountId=" + bankAccountId));
    }

    /** 감사 스냅샷. accountNumber는 Masker가 값 전체를 마스킹하고, 끝 4자리는 suffix로 별도 기록한다(LinkedHashMap·null 허용). */
    private static Map<String, Object> snapshot(SellerBankAccount account) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("bankCode", account.getBankCode());
        fields.put("accountNumber", account.getAccountNumber());
        fields.put("accountNumberSuffix", account.accountNumberSuffix());
        fields.put("accountHolder", account.getAccountHolder());
        fields.put("isPrimary", account.isPrimary());
        fields.put("status", account.getStatus().name());
        return fields;
    }
}
