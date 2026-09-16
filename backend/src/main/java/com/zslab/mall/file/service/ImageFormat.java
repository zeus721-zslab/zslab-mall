package com.zslab.mall.file.service;

import java.util.Arrays;
import java.util.Optional;

/**
 * 허용 이미지 형식(Track 77·jpg·png·webp)과 매직 바이트 판별. 확장자·Content-Type은 클라이언트가 위조할 수 있으므로 신뢰하지 않고
 * 파일 선두 바이트로만 판정한다. 값 목록은 업로드 정책(D-166)이라 4층위 enum 잠금 대상 컬럼이 아니다.
 */
public enum ImageFormat {
    JPEG("jpg", "image/jpeg", "jpg"),
    PNG("png", "image/png", "png"),
    /** JDK ImageIO는 webp 쓰기를 지원하지 않아(읽기는 TwelveMonkeys) 썸네일은 png로 기록한다(알파 보존). */
    WEBP("webp", "image/webp", "png");

    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] RIFF_MAGIC = {'R', 'I', 'F', 'F'};
    private static final byte[] WEBP_MAGIC = {'W', 'E', 'B', 'P'};
    private static final int WEBP_TAG_OFFSET = 8;

    private final String extension;
    private final String contentType;
    private final String thumbnailExtension;

    ImageFormat(String extension, String contentType, String thumbnailExtension) {
        this.extension = extension;
        this.contentType = contentType;
        this.thumbnailExtension = thumbnailExtension;
    }

    public String extension() {
        return extension;
    }

    public String contentType() {
        return contentType;
    }

    public String thumbnailExtension() {
        return thumbnailExtension;
    }

    /** ImageIO 쓰기용 포맷 이름(썸네일). */
    public String thumbnailWriterName() {
        return thumbnailExtension;
    }

    /** 선두 바이트로 형식을 판별한다. 허용 3형식이 아니면 empty. */
    public static Optional<ImageFormat> detect(byte[] content) {
        if (startsWith(content, JPEG_MAGIC, 0)) {
            return Optional.of(JPEG);
        }
        if (startsWith(content, PNG_MAGIC, 0)) {
            return Optional.of(PNG);
        }
        if (startsWith(content, RIFF_MAGIC, 0) && startsWith(content, WEBP_MAGIC, WEBP_TAG_OFFSET)) {
            return Optional.of(WEBP);
        }
        return Optional.empty();
    }

    /** 파일 확장자로 서빙 Content-Type을 찾는다(저장 시 형식별 확장자를 고정했으므로 서빙은 확장자 신뢰 가능). */
    public static Optional<ImageFormat> fromExtension(String extension) {
        return Arrays.stream(values()).filter(format -> format.extension.equalsIgnoreCase(extension)).findFirst();
    }

    private static boolean startsWith(byte[] content, byte[] magic, int offset) {
        if (content == null || content.length < offset + magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (content[offset + i] != magic[i]) {
                return false;
            }
        }
        return true;
    }
}
