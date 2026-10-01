package com.zslab.mall.inbox.exception;

/**
 * 보류하려는 항목이 지금 본인 인박스의 대기 항목이 아닐 때 발생한다(404 INBOX_ITEM_NOT_FOUND). 원천 미존재·이미 처리됨·타 셀러 항목을
 * 구분하지 않는다(타 셀러 항목 존재 은닉).
 */
public class InboxItemNotFoundException extends RuntimeException {

    public InboxItemNotFoundException(String message) {
        super(message);
    }
}
