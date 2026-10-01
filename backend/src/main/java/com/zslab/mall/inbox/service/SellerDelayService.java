package com.zslab.mall.inbox.service;

import com.zslab.mall.inbox.collector.SellerDelay;
import com.zslab.mall.inbox.collector.SellerDelayCounter;
import com.zslab.mall.inbox.controller.response.SellerDelayNudgeResponse;
import com.zslab.mall.inbox.controller.response.SellerDelayResponse;
import com.zslab.mall.inbox.enums.SellerNudgeResult;
import com.zslab.mall.notification.enums.NotificationLogStatus;
import com.zslab.mall.notification.service.NotificationService;
import com.zslab.mall.notification.service.NotificationService.SellerSmsRecipient;
import com.zslab.mall.notification.template.NotificationMessages;
import com.zslab.mall.seller.entity.Seller;
import com.zslab.mall.seller.exception.SellerNotFoundException;
import com.zslab.mall.seller.repository.SellerRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 셀러 지연 독촉(D-252). 셀러마다 지연을 다시 세고(NO_DELAY) → 24시간 쿨다운(COOLDOWN) → 수신처(NO_RECIPIENT) → 발송(SENT·FAILED) 순으로
 * 판정한다. 셀러별로 독립 처리하며 본 클래스는 의도적으로 {@code @Transactional}이 없다 — 앞 셀러의 발송 기록이 뒤 셀러 처리와 무관하게 남는다
 * (클레임 일괄 승인 {@code AdminClaimBulkApproveService} 선례). 발송 실패는 비전파형 dispatch가 FAILED로 흡수한다.
 */
@Slf4j
@Service
public class SellerDelayService {

    /** 같은 셀러에 독촉을 다시 보낼 수 있기까지의 간격(SENT 기준). */
    static final Duration NUDGE_COOLDOWN = Duration.ofHours(24);

    private final SellerRepository sellerRepository;
    private final SellerDelayCounter sellerDelayCounter;
    private final NotificationService notificationService;

    public SellerDelayService(SellerRepository sellerRepository, SellerDelayCounter sellerDelayCounter,
            NotificationService notificationService) {
        this.sellerRepository = sellerRepository;
        this.sellerDelayCounter = sellerDelayCounter;
        this.notificationService = notificationService;
    }

    /**
     * 셀러 지연 패널 정보 — 유형별 초과 건수를 지금 다시 세고 마지막 독촉 시각을 붙인다(지연 0건이어도 200).
     *
     * @throws SellerNotFoundException 셀러가 없는 경우(404)
     */
    public SellerDelayResponse get(String sellerPublicId) {
        Seller seller = sellerRepository.findByPublicId(sellerPublicId)
                .orElseThrow(() -> new SellerNotFoundException("판매자를 찾을 수 없습니다: " + sellerPublicId));
        SellerDelay delay = sellerDelayCounter.countOf(seller.getId(), LocalDateTime.now());
        return new SellerDelayResponse(seller.getPublicId(), seller.getCompanyName(), delay.deliveryReadyCount(),
                delay.questionUnansweredCount(), notificationService.lastSellerDelayNudgeSentAt(seller.getId()).orElse(null));
    }

    public SellerDelayNudgeResponse nudge(List<String> sellerPublicIds) {
        List<SellerDelayNudgeResponse.Item> results = new ArrayList<>();
        for (String sellerPublicId : sellerPublicIds) {
            results.add(new SellerDelayNudgeResponse.Item(sellerPublicId, nudgeOne(sellerPublicId)));
        }
        SellerDelayNudgeResponse response = SellerDelayNudgeResponse.of(results);
        log.info("[SellerDelay] 독촉 requested={} sent={} failed={} noRecipient={} cooldown={} noDelay={}", sellerPublicIds.size(),
                response.sentCount(), response.failedCount(), response.noRecipientCount(), response.cooldownCount(),
                response.noDelayCount());
        return response;
    }

    private SellerNudgeResult nudgeOne(String sellerPublicId) {
        LocalDateTime now = LocalDateTime.now();
        // 없는 셀러는 지연 0건과 같게 다룬다 — 인박스 행이 사라진 뒤의 요청과 구분할 이유가 없다.
        Optional<Seller> seller = sellerRepository.findByPublicId(sellerPublicId);
        if (seller.isEmpty()) {
            return SellerNudgeResult.NO_DELAY;
        }
        Long sellerId = seller.get().getId();
        SellerDelay delay = sellerDelayCounter.countOf(sellerId, now);
        if (!delay.hasDelay()) {
            return SellerNudgeResult.NO_DELAY;
        }
        boolean coolingDown = notificationService.lastSellerDelayNudgeSentAt(sellerId)
                .filter(sentAt -> sentAt.isAfter(now.minus(NUDGE_COOLDOWN)))
                .isPresent();
        if (coolingDown) {
            return SellerNudgeResult.COOLDOWN;
        }
        Optional<SellerSmsRecipient> recipient = notificationService.resolveSellerSmsRecipient(sellerId);
        if (recipient.isEmpty()) {
            return SellerNudgeResult.NO_RECIPIENT;
        }
        String content = String.format(NotificationMessages.SELLER_DELAY_NUDGE_SMS, delay.deliveryReadyCount(),
                delay.questionUnansweredCount());
        NotificationLogStatus status = notificationService.sendSellerDelayNudge(sellerId, recipient.get(), content);
        return status == NotificationLogStatus.SENT ? SellerNudgeResult.SENT : SellerNudgeResult.FAILED;
    }
}
