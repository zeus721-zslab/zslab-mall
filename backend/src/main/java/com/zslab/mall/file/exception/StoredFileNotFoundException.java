package com.zslab.mall.file.exception;

/** 업로드 파일 서빙 대상이 없거나 루트 밖 경로일 때 발생한다(Track 77·404 FILE_NOT_FOUND·존재 여부 은닉). */
public class StoredFileNotFoundException extends RuntimeException {

    public StoredFileNotFoundException(String message) {
        super(message);
    }
}
