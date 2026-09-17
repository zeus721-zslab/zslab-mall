package com.zslab.mall.attachment.repository;

/** 대상별 첨부 개수 프로젝션(Track 81-B·관리자 클레임 목록 배치). */
public interface AttachmentCountProjection {

    Long getTargetId();

    long getAttachmentCount();
}
