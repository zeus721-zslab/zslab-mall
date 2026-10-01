package com.zslab.mall.inbox.stream;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 인박스 대기 항목이 바뀌는 쓰기 지점에서 부르는 변경 신호 발행기(D-249).
 *
 * <p><b>커밋 후 1회</b>: 트랜잭션 안의 호출은 트랜잭션 동기화에 모았다가 커밋 직후 범위별로 한 번만 보낸다(한 트랜잭션의 여러 발행 = 1신호).
 * 롤백되면 보내지 않는다. 트랜잭션 밖 호출은 즉시 보낸다. 모음은 현재 트랜잭션의 동기화 목록에서 찾으므로, REQUIRES_NEW로 끼어든 트랜잭션은
 * 바깥과 섞이지 않고 자기 커밋에서 따로 보낸다. 커밋 후 콜백(AFTER_COMMIT 리스너 등)에서 쓰기를 하며 발행하려면 REQUIRES_NEW로 새 트랜잭션을
 * 열어야 한다 — 커밋 후 단계에 등록한 동기화는 불리지 않아 신호가 사라진다.
 *
 * <p><b>셀러 범위</b>: 발행 지점이 sellerId를 이미 손에 쥐고 있으면 {@link #sellerChanged}, 아니면 {@link #allSellersChanged}. sellerId를
 * 얻으려고 조회를 추가하지 않는다 — 셀러 전체에 보내도 각 셀러는 자기 인박스를 다시 읽을 뿐이다.
 */
@Component
public class InboxSignalPublisher {

    private final InboxStreamRegistry inboxStreamRegistry;

    public InboxSignalPublisher(InboxStreamRegistry inboxStreamRegistry) {
        this.inboxStreamRegistry = inboxStreamRegistry;
    }

    public void adminChanged() {
        record(signal -> signal.admins = true);
    }

    public void sellerChanged(long sellerId) {
        record(signal -> signal.sellerIds.add(sellerId));
    }

    public void allSellersChanged() {
        record(signal -> signal.allSellers = true);
    }

    private void record(Consumer<PendingSignal> change) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            PendingSignal signal = new PendingSignal();
            change.accept(signal);
            signal.send();
            return;
        }
        change.accept(currentTransactionSignal());
    }

    private PendingSignal currentTransactionSignal() {
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            if (synchronization instanceof PendingSignal signal) {
                return signal;
            }
        }
        PendingSignal signal = new PendingSignal();
        TransactionSynchronizationManager.registerSynchronization(signal);
        return signal;
    }

    /** 한 트랜잭션 동안 모은 수신 범위. 트랜잭션은 한 스레드에 묶이므로 동기화가 필요 없다. */
    private final class PendingSignal implements TransactionSynchronization {

        private boolean admins;
        private boolean allSellers;
        private final Set<Long> sellerIds = new HashSet<>();

        @Override
        public void afterCommit() {
            send();
        }

        private void send() {
            inboxStreamRegistry.broadcast(new InboxSignal(admins, allSellers, Set.copyOf(sellerIds)));
        }
    }
}
