package com.zslab.mall.inquiry.service;

import com.zslab.mall.inquiry.controller.response.MyInquiryResponse;
import com.zslab.mall.inquiry.entity.Inquiry;
import com.zslab.mall.inquiry.repository.InquiryRepository;
import com.zslab.mall.order.controller.response.PagedResponse;
import com.zslab.mall.order.entity.Order;
import com.zslab.mall.order.repository.OrderRepository;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 구매자 내 문의 조회(Track 106-4). 페이징은 106-2 내 질문 기준(기본 10·최대 50·최신순)이다. 조회는 확인 처리를 하지 않는다 — 미확인 해제는
 * 확인 API만 한다.
 */
@Service
@Transactional(readOnly = true)
public class InquiryQueryService {

    static final int DEFAULT_PAGE_SIZE = 10;
    static final int MAX_PAGE_SIZE = 50;
    static final Sort LATEST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final InquiryRepository inquiryRepository;
    private final OrderRepository orderRepository;

    public InquiryQueryService(InquiryRepository inquiryRepository, OrderRepository orderRepository) {
        this.inquiryRepository = inquiryRepository;
        this.orderRepository = orderRepository;
    }

    /** 내 문의 목록(삭제 제외·최신순·답변 포함). 페이지 문의의 첨부 주문은 한 번에 배치 조회한다. */
    public PagedResponse<MyInquiryResponse> listMine(Long buyerId, int page, int size) {
        Page<Inquiry> inquiries = inquiryRepository.findByBuyerId(buyerId, PageRequest.of(Math.max(page, 0), clampSize(size), LATEST));
        Map<Long, Order> orders = ordersById(inquiries.getContent(), orderRepository);
        return PagedResponse.from(inquiries.map(inquiry -> {
            Order order = inquiry.getOrderId() == null ? null : orders.get(inquiry.getOrderId());
            return new MyInquiryResponse(
                    inquiry.getPublicId(),
                    inquiry.getCategory(),
                    inquiry.getContent(),
                    order == null ? null : order.getPublicId(),
                    order == null ? null : order.getOrderNo(),
                    inquiry.getAnswerContent(),
                    inquiry.getAnsweredAt(),
                    !inquiry.isAnswered(),
                    !inquiry.isAnswered(),
                    inquiry.isAnswerUnchecked(),
                    inquiry.getCreatedAt());
        }));
    }

    /** 페이지 문의들의 첨부 주문(주문 없는 문의는 건너뛴다). 관리자 목록도 쓴다. */
    static Map<Long, Order> ordersById(List<Inquiry> inquiries, OrderRepository orderRepository) {
        List<Long> orderIds = inquiries.stream().map(Inquiry::getOrderId).filter(Objects::nonNull).distinct().toList();
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        return orderRepository.findAllById(orderIds).stream().collect(Collectors.toMap(Order::getId, Function.identity()));
    }

    static int clampSize(int size) {
        if (size < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
