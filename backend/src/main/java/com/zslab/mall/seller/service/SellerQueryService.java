package com.zslab.mall.seller.service;

import com.zslab.mall.seller.controller.response.SellerSummaryResponse;
import com.zslab.mall.seller.repository.SellerRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 셀러 조회 서비스(Track 76·read-only). Controller의 Repository 직접 접근(D-43.11)을 피하는 읽기 계층이다.
 * 셀러 수가 소량(단일 운영자·입점 수동)이라 페이징 없이 전량을 회사명 순으로 반환한다.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SellerQueryService {

    private final SellerRepository sellerRepository;

    /** 전체 셀러(soft-delete 제외·@SQLRestriction) 회사명 오름차순. */
    public List<SellerSummaryResponse> listAll() {
        return sellerRepository.findAll(Sort.by(Sort.Direction.ASC, "companyName", "id")).stream()
                .map(SellerSummaryResponse::from)
                .toList();
    }
}
