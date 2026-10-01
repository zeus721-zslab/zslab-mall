package com.zslab.mall.inbox.service;

import com.zslab.mall.common.exception.MalformedRequestException;
import com.zslab.mall.inbox.collector.InboxSource;
import com.zslab.mall.inbox.collector.InboxViewer;
import com.zslab.mall.inbox.controller.request.InboxSnoozeRequest;
import com.zslab.mall.inbox.enums.InboxItemType;
import com.zslab.mall.inbox.exception.InboxItemNotFoundException;
import com.zslab.mall.inbox.repository.InboxSnoozeRepository;
import com.zslab.mall.inbox.stream.InboxSignalPublisher;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 인박스 보류·해제(D-248 만료형 오버레이). 보류는 보는 사람 본인(userId) 소유이며 원천 상태를 바꾸지 않는다.
 */
@Service
@Transactional
public class InboxSnoozeService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** until_at DATETIME(6)이 담을 수 있는 마지막 시각. 기간 상한은 두지 않지만(D-248) 컬럼 범위를 넘으면 저장이 실패한다. */
    private static final LocalDateTime MAX_UNTIL_AT = LocalDateTime.of(9999, 12, 31, 23, 59, 59);

    private final InboxQueryService inboxQueryService;
    private final InboxSnoozeRepository inboxSnoozeRepository;
    private final InboxSignalPublisher inboxSignalPublisher;

    public InboxSnoozeService(InboxQueryService inboxQueryService, InboxSnoozeRepository inboxSnoozeRepository,
            InboxSignalPublisher inboxSignalPublisher) {
        this.inboxQueryService = inboxQueryService;
        this.inboxSnoozeRepository = inboxSnoozeRepository;
        this.inboxSignalPublisher = inboxSignalPublisher;
    }

    /**
     * 보류를 만들거나 기한·사유를 덮어쓴다.
     *
     * @throws MalformedRequestException  보류 시각이 KST 9999-12-31 23:59:59를 넘는 경우(400)
     * @throws InboxItemNotFoundException 대상이 지금 본인 인박스의 대기 항목이 아닌 경우(다른 역할 유형·타 셀러 항목·이미 처리됨 포함·404)
     */
    public void snooze(InboxViewer viewer, InboxSnoozeRequest request) {
        LocalDateTime untilAt = request.untilAt().atZoneSameInstant(KST).toLocalDateTime();
        if (untilAt.isAfter(MAX_UNTIL_AT)) {
            throw new MalformedRequestException("보류 시각이 너무 먼 미래입니다: " + request.untilAt());
        }
        requirePending(viewer, request.type(), request.ref());
        inboxSnoozeRepository.upsert(viewer.userId(), request.type().name(), request.ref(), untilAt, request.reason().strip(),
                LocalDateTime.now());
        signalViewerAudience(viewer);
    }

    /** 본인 보류만 지운다. 없으면 아무 일도 하지 않는다(멱등). */
    public void unsnooze(InboxViewer viewer, InboxItemType type, String ref) {
        inboxSnoozeRepository.deleteByOwnerUserIdAndItemTypeAndItemRef(viewer.userId(), type, ref);
        signalViewerAudience(viewer);
    }

    /** 보류는 본인 소유지만 같은 사람이 연 다른 탭도 갱신되도록 그 역할 범위(셀러는 소속 셀러)에 알린다. */
    private void signalViewerAudience(InboxViewer viewer) {
        if (viewer.isSeller()) {
            inboxSignalPublisher.sellerChanged(viewer.sellerId());
        } else {
            inboxSignalPublisher.adminChanged();
        }
    }

    private void requirePending(InboxViewer viewer, InboxItemType type, String ref) {
        boolean pending = inboxQueryService.sourceFor(viewer, type)
                .map((InboxSource source) -> source.isPending(viewer, ref))
                .orElse(false);
        if (!pending) {
            throw new InboxItemNotFoundException("인박스 대기 항목을 찾을 수 없습니다: type=" + type + " ref=" + ref);
        }
    }
}
