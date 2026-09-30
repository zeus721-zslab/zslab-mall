package com.zslab.mall.inquiry.service;

import com.zslab.mall.common.util.EmailMasker;
import com.zslab.mall.inquiry.controller.response.AdminInquiryResponse;
import com.zslab.mall.inquiry.entity.Inquiry;
import com.zslab.mall.inquiry.enums.InquiryAnsweredFilter;
import com.zslab.mall.inquiry.enums.InquiryCategory;
import com.zslab.mall.inquiry.repository.InquiryRepository;
import com.zslab.mall.order.controller.response.PagedResponse;
import com.zslab.mall.order.entity.Order;
import com.zslab.mall.order.repository.OrderRepository;
import com.zslab.mall.user.entity.User;
import com.zslab.mall.user.repository.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 문의 목록(Track 106-4). 답변 여부·카테고리 필터 · 오래된 순(미답변부터 처리하는 대기열·106-2 셀러 목록 선례) · 삭제 제외. 페이지 문의의
 * 첨부 주문과 작성자는 한 번에 배치 조회한다.
 */
@Service
@Transactional(readOnly = true)
public class AdminInquiryQueryService {

    private static final Sort OLDEST = Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id"));

    private final InquiryRepository inquiryRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;

    public AdminInquiryQueryService(InquiryRepository inquiryRepository, OrderRepository orderRepository, UserRepository userRepository) {
        this.inquiryRepository = inquiryRepository;
        this.orderRepository = orderRepository;
        this.userRepository = userRepository;
    }

    /** @param category 카테고리 필터(없으면 null = 전체) */
    public PagedResponse<AdminInquiryResponse> list(InquiryAnsweredFilter answered, InquiryCategory category, int page, int size) {
        Page<Inquiry> inquiries = inquiryRepository.findForAdmin(answered.answeredCondition(), category,
                PageRequest.of(Math.max(page, 0), InquiryQueryService.clampSize(size), OLDEST));
        List<Inquiry> content = inquiries.getContent();
        Map<Long, Order> orders = InquiryQueryService.ordersById(content, orderRepository);
        Map<Long, User> buyers = buyersById(content);
        return PagedResponse.from(inquiries.map(inquiry -> {
            Order order = inquiry.getOrderId() == null ? null : orders.get(inquiry.getOrderId());
            User buyer = buyers.get(inquiry.getBuyerId());
            return new AdminInquiryResponse(
                    inquiry.getPublicId(),
                    inquiry.getCategory(),
                    inquiry.getContent(),
                    order == null ? null : order.getPublicId(),
                    order == null ? null : order.getOrderNo(),
                    buyer == null ? null : EmailMasker.mask(buyer.getEmail()),
                    inquiry.getAnswerContent(),
                    inquiry.getAnsweredAt(),
                    inquiry.getCreatedAt());
        }));
    }

    /** 페이지 문의들의 작성자(삭제 회원은 맵에 없음). */
    private Map<Long, User> buyersById(List<Inquiry> inquiries) {
        if (inquiries.isEmpty()) {
            return Map.of();
        }
        return userRepository.findAllById(inquiries.stream().map(Inquiry::getBuyerId).distinct().toList()).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
    }
}
