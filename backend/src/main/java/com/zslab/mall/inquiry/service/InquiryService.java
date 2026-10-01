package com.zslab.mall.inquiry.service;

import com.zslab.mall.inbox.stream.InboxSignalPublisher;
import com.zslab.mall.inquiry.entity.Inquiry;
import com.zslab.mall.inquiry.enums.InquiryCategory;
import com.zslab.mall.inquiry.exception.InquiryInvalidStateException;
import com.zslab.mall.inquiry.exception.InquiryNotFoundException;
import com.zslab.mall.inquiry.repository.InquiryRepository;
import com.zslab.mall.order.entity.Order;
import com.zslab.mall.order.exception.OrderNotFoundException;
import com.zslab.mall.order.repository.OrderRepository;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 구매자 운영자 문의 쓰기(Track 106-4): 등록·수정·삭제·답변 확인. 중복 제출 방어는 서버에 두지 않는다(106-2 D-239 선례). 수정·삭제·확인은
 * 문의 행 락으로 관리자 답변과 직렬화한다.
 */
@Slf4j
@Service
@Transactional
public class InquiryService {

    private final InquiryRepository inquiryRepository;
    private final OrderRepository orderRepository;
    private final InboxSignalPublisher inboxSignalPublisher;

    public InquiryService(InquiryRepository inquiryRepository, OrderRepository orderRepository,
            InboxSignalPublisher inboxSignalPublisher) {
        this.inquiryRepository = inquiryRepository;
        this.orderRepository = orderRepository;
        this.inboxSignalPublisher = inboxSignalPublisher;
    }

    /**
     * 문의를 등록한다. 본문은 trim해 저장한다(형식은 DTO가 trim 후 5~500자로 선검증).
     *
     * @param orderPublicId 첨부 주문 public_id(없으면 null)
     * @throws OrderNotFoundException 첨부 주문 미존재·타인 주문(404 — 구매자 주문 조회와 같은 판정)
     */
    public Inquiry create(Long buyerId, InquiryCategory category, String content, String orderPublicId) {
        Long orderId = orderPublicId == null ? null : requireOwnOrder(buyerId, orderPublicId).getId();
        Inquiry inquiry = inquiryRepository.save(Inquiry.create(buyerId, orderId, category, content.trim()));
        log.info("[Inquiry] 등록 inquiryPublicId={} buyerId={} orderId={}", inquiry.getPublicId(), buyerId, orderId);
        inboxSignalPublisher.adminChanged(); // 관리자 미답변 문의 진입
        return inquiry;
    }

    /**
     * 구매자 수정(카테고리·본문 교체). 미답변일 때만 가능하다.
     *
     * @throws InquiryNotFoundException 미존재·삭제·타인 문의(404)
     * @throws InquiryInvalidStateException 답변 완료 문의(422)
     */
    public void update(Long buyerId, String inquiryPublicId, InquiryCategory category, String content) {
        Inquiry inquiry = requireOwnInquiryForUpdate(buyerId, inquiryPublicId);
        try {
            inquiry.edit(category, content.trim());
        } catch (IllegalStateException exception) {
            throw new InquiryInvalidStateException(exception.getMessage() + " inquiryPublicId=" + inquiryPublicId);
        }
        log.info("[Inquiry] 수정 inquiryPublicId={} buyerId={}", inquiryPublicId, buyerId);
    }

    /**
     * 구매자 삭제(soft delete). 미답변일 때만 가능하다.
     *
     * @throws InquiryNotFoundException 미존재·이미 삭제·타인 문의(404)
     * @throws InquiryInvalidStateException 답변 완료 문의(422)
     */
    public void delete(Long buyerId, String inquiryPublicId) {
        Inquiry inquiry = requireOwnInquiryForUpdate(buyerId, inquiryPublicId);
        try {
            inquiry.deleteByAuthor();
        } catch (IllegalStateException exception) {
            throw new InquiryInvalidStateException(exception.getMessage() + " inquiryPublicId=" + inquiryPublicId);
        }
        log.info("[Inquiry] 삭제 inquiryPublicId={} buyerId={}", inquiryPublicId, buyerId);
        inboxSignalPublisher.adminChanged(); // 관리자 미답변 문의 이탈
    }

    /**
     * 답변 확인(미확인 표시 해제). 이미 확인한 답변이면 아무것도 바꾸지 않는다(멱등).
     *
     * @throws InquiryNotFoundException 미존재·삭제·타인 문의(404)
     * @throws InquiryInvalidStateException 미답변 문의(422 — 106-2의 상태 위반 = 422 관례)
     */
    public void checkAnswer(Long buyerId, String inquiryPublicId) {
        Inquiry inquiry = requireOwnInquiryForUpdate(buyerId, inquiryPublicId);
        try {
            inquiry.checkAnswer(LocalDateTime.now());
        } catch (IllegalStateException exception) {
            throw new InquiryInvalidStateException(exception.getMessage() + " inquiryPublicId=" + inquiryPublicId);
        }
    }

    /** 락을 잡고 작성자 대조. 타인 문의도 미존재와 같은 404로 은닉한다. */
    private Inquiry requireOwnInquiryForUpdate(Long buyerId, String inquiryPublicId) {
        Inquiry inquiry = inquiryRepository.findByPublicIdForUpdate(inquiryPublicId)
                .orElseThrow(() -> new InquiryNotFoundException("문의를 찾을 수 없습니다: " + inquiryPublicId));
        if (!inquiry.isWrittenBy(buyerId)) {
            throw new InquiryNotFoundException("문의를 찾을 수 없습니다: " + inquiryPublicId);
        }
        return inquiry;
    }

    /** 첨부 주문 소유 대조. 타인 주문도 미존재와 같은 404(BuyerOrderQueryService.getOrder 선례). */
    private Order requireOwnOrder(Long buyerId, String orderPublicId) {
        Order order = orderRepository.findByPublicId(orderPublicId)
                .orElseThrow(() -> new OrderNotFoundException("주문을 찾을 수 없습니다: " + orderPublicId));
        if (!order.getBuyerId().equals(buyerId)) {
            throw new OrderNotFoundException("주문을 찾을 수 없습니다: " + orderPublicId);
        }
        return order;
    }
}
