package com.zslab.mall.seller.exception;

import com.zslab.mall.seller.service.SellerTerminationBlock;
import java.util.List;

/**
 * 미지급 정산·진행 중 품목·활성 클레임이 있는 판매자의 종료(TERMINATED) 차단(Track 89-D·409 SELLER_ACTIVITY_IN_PROGRESS).
 * 어느 가드에 몇 건이 걸렸는지 {@link #getBlocks}로 노출한다({@code MemberActivityInProgressException} 셀러 판).
 */
public class SellerActivityInProgressException extends RuntimeException {

    private final List<SellerTerminationBlock> blocks;

    public SellerActivityInProgressException(String message, List<SellerTerminationBlock> blocks) {
        super(message);
        this.blocks = List.copyOf(blocks);
    }

    public List<SellerTerminationBlock> getBlocks() {
        return blocks;
    }
}
