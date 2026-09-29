package com.zslab.mall.file.service;

import com.twelvemonkeys.imageio.metadata.Directory;
import com.twelvemonkeys.imageio.metadata.Entry;
import com.twelvemonkeys.imageio.metadata.jpeg.JPEG;
import com.twelvemonkeys.imageio.metadata.jpeg.JPEGSegment;
import com.twelvemonkeys.imageio.metadata.jpeg.JPEGSegmentUtil;
import com.twelvemonkeys.imageio.metadata.tiff.TIFF;
import com.twelvemonkeys.imageio.metadata.tiff.TIFFReader;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import lombok.extern.slf4j.Slf4j;

/**
 * 원본 재인코딩(Track 106-1·리뷰 사진 공개 서빙). 업로드 바이트를 그대로 저장하면 EXIF(GPS·촬영 기기)가 익명 사용자에게 노출되므로, 디코딩한
 * 픽셀을 메타데이터 없이 다시 기록한다. 재기록하면 뷰어가 회전을 보정할 EXIF Orientation도 사라지므로, 기록 전에 Orientation(1~8)을
 * 픽셀에 적용해 휴대폰 세로 사진이 눕지 않게 한다. 고화소 사진은 서브샘플링으로 줄여 읽고 긴 변 {@value #MAX_LONG_SIDE_PX}px로 맞춘다
 * (순서: 서브샘플링 디코딩 → 회전·축소(한 번에 그림) → 재기록).
 *
 * <p>JPEG만 Orientation을 읽는다(APP1 Exif). PNG는 eXIf 청크 회전 보정 관례가 뷰어마다 달라 읽지 않고, 재기록으로 메타데이터 청크만 없앤다.
 * JPEG과 알파 없는 PNG는 RGB, 알파 있는 PNG는 ARGB로 새로 그려(투명 보존) writer가 받지 못하는 원시 타입을 피한다.
 */
@Slf4j
final class ImageReencoder {

    /** EXIF Orientation 기본값(회전·반전 없음). */
    static final int ORIENTATION_NORMAL = 1;
    private static final int ORIENTATION_MAX = 8;
    /** 5~8(전치·90도 회전 계열)은 보정 후 가로·세로가 바뀐다. */
    private static final int FIRST_SIDE_SWAPPING_ORIENTATION = 5;
    /** 저장본 긴 변 상한. 리뷰 사진 표시(상세 확대 보기 포함)에 충분하고 공개 서빙 용량을 줄인다. */
    static final int MAX_LONG_SIDE_PX = 2048;
    /** 재압축 품질(0~1). JDK 기본(0.75)은 사진 재압축 손실이 눈에 띄어 명시한다. */
    private static final float JPEG_QUALITY = 0.9f;
    private static final String EXIF_IDENTIFIER = "Exif";
    private static final String JPEG_WRITER_NAME = "jpeg";
    private static final String PNG_WRITER_NAME = "png";

    private ImageReencoder() {
    }

    /**
     * 긴 변이 {@value #MAX_LONG_SIDE_PX}px 이상 남는 가장 큰 정수 서브샘플링 배율(1 이상 — 줄여 읽은 버퍼가 가장 작다). ImageIO는 배율 s로
     * 긴 변을 ⌈L/s⌉로 읽으므로 ⌈L/s⌉ ≥ 2048 ⇔ L/s > 2047 ⇔ s < L/2047이고, 그런 가장 큰 정수는 ⌈L/2047⌉ − 1이다.
     * 긴 변이 2048 이하이면 1(그대로 읽음).
     */
    static int subsamplingFor(int longSide) {
        return Math.max(1, Math.ceilDiv(longSide, MAX_LONG_SIDE_PX - 1) - 1);
    }

    /** 회전·축소 결과 버퍼의 최대 화소 수(디코딩 예산 계산용 — 결과는 긴 변 2048 이하). */
    static long maxResizedPixels() {
        return (long) MAX_LONG_SIDE_PX * MAX_LONG_SIDE_PX;
    }

    /**
     * 소스 서브샘플링으로 줄여 디코딩한다(가로·세로 모두 {@code subsampling}픽셀마다 1픽셀). 원본 전체 크기 버퍼를 만들지 않아 고화소 사진도
     * 디코딩 예산 안에서 읽는다. reader가 없거나 디코딩에 실패하면 null(호출부가 INVALID_IMAGE로 돌린다).
     */
    static BufferedImage decodeSubsampled(byte[] content, int subsampling) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            if (input == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                ImageReadParam param = reader.getDefaultReadParam();
                param.setSourceSubsampling(subsampling, subsampling, 0, 0);
                return reader.read(0, param);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException exception) {
            // 디코딩 실패는 "읽을 수 없는 이미지"로 파일별 실패 처리한다(요청 전체 실패 아님·기존 decode와 같은 계약).
            log.warn("[ImageReencoder] 서브샘플링 디코딩 실패 subsampling={}: {}", subsampling, exception.getMessage());
            return null;
        }
    }

    /**
     * JPEG APP1 Exif의 Orientation 값을 읽는다. PNG·Exif 없음·판독 실패·범위 밖 값이면 {@link #ORIENTATION_NORMAL}.
     */
    static int readOrientation(byte[] content, ImageFormat format) {
        if (format != ImageFormat.JPEG) {
            return ORIENTATION_NORMAL;
        }
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            List<JPEGSegment> segments = JPEGSegmentUtil.readSegments(input, JPEG.APP1, EXIF_IDENTIFIER);
            if (segments.isEmpty()) {
                return ORIENTATION_NORMAL;
            }
            return orientationOf(segments.get(0));
        } catch (IOException | RuntimeException exception) {
            // Orientation은 표시 보정용 부가 정보라 판독 실패로 업로드를 막지 않는다(회전 없이 재인코딩·EXIF 제거는 그대로).
            log.warn("[ImageReencoder] Orientation 판독 실패 — 회전 없이 진행: {}", exception.getMessage());
            return ORIENTATION_NORMAL;
        }
    }

    private static int orientationOf(JPEGSegment exifSegment) throws IOException {
        byte[] data;
        try (InputStream segmentData = exifSegment.data()) {
            data = segmentData.readAllBytes();
        }
        // 식별자 "Exif\0" 뒤의 패딩 0 바이트 1개를 건너뛰어 TIFF 헤더(II·MM)부터 읽는다.
        int offset = data.length > 0 && data[0] == 0 ? 1 : 0;
        try (ImageInputStream tiff = ImageIO.createImageInputStream(new ByteArrayInputStream(data, offset, data.length - offset))) {
            Directory directory = new TIFFReader().read(tiff);
            Entry entry = directory.getEntryById(TIFF.TAG_ORIENTATION);
            if (entry == null || !(entry.getValue() instanceof Number value)) {
                return ORIENTATION_NORMAL;
            }
            int orientation = value.intValue();
            return orientation >= ORIENTATION_NORMAL && orientation <= ORIENTATION_MAX ? orientation : ORIENTATION_NORMAL;
        }
    }

    /**
     * Orientation 회전과 긴 변 {@value #MAX_LONG_SIDE_PX}px 축소를 한 번에 그린다(항상 새 버퍼 — 회전·축소가 없어도 writer 호환 타입으로
     * 정규화). 회전본을 따로 만들지 않으므로 추가 버퍼는 결과 크기(긴 변 2048 이하)뿐이다. 축소는 bilinear이며 서브샘플링 뒤라 비율이
     * 2배 미만이다. 5~8은 가로·세로가 바뀐다.
     */
    static BufferedImage orientAndFit(BufferedImage source, int orientation, ImageFormat format) {
        int width = source.getWidth();
        int height = source.getHeight();
        boolean swapsSides = orientation >= FIRST_SIDE_SWAPPING_ORIENTATION;
        int orientedWidth = swapsSides ? height : width;
        int orientedHeight = swapsSides ? width : height;
        double scale = Math.min(1.0, (double) MAX_LONG_SIDE_PX / Math.max(orientedWidth, orientedHeight));
        int targetWidth = Math.max(1, (int) Math.round(orientedWidth * scale));
        int targetHeight = Math.max(1, (int) Math.round(orientedHeight * scale));
        // 알파가 없는 PNG까지 ARGB로 그리면 쓸모없는 알파 채널로 재기록 크기만 커진다.
        boolean keepsAlpha = format != ImageFormat.JPEG && source.getColorModel().hasAlpha();
        int imageType = keepsAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage result = new BufferedImage(targetWidth, targetHeight, imageType);
        AffineTransform transform = AffineTransform.getScaleInstance(
                (double) targetWidth / orientedWidth, (double) targetHeight / orientedHeight);
        transform.concatenate(transformFor(orientation, width, height));
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, transform, null);
        } finally {
            graphics.dispose();
        }
        return result;
    }

    /** EXIF Orientation → 원본 좌표를 보정 좌표로 옮기는 변환(1 = 항등). */
    private static AffineTransform transformFor(int orientation, int width, int height) {
        AffineTransform transform = new AffineTransform();
        switch (orientation) {
            case 2 -> { // 좌우 반전
                transform.translate(width, 0);
                transform.scale(-1, 1);
            }
            case 3 -> { // 180도
                transform.translate(width, height);
                transform.rotate(Math.PI);
            }
            case 4 -> { // 상하 반전
                transform.translate(0, height);
                transform.scale(1, -1);
            }
            case 5 -> { // 전치(주대각선 반전)
                transform.rotate(Math.PI / 2);
                transform.scale(1, -1);
            }
            case 6 -> { // 시계 방향 90도
                transform.translate(height, 0);
                transform.rotate(Math.PI / 2);
            }
            case 7 -> { // 반대 대각선 반전
                transform.translate(height, width);
                transform.rotate(Math.PI / 2);
                transform.scale(-1, 1);
            }
            case 8 -> { // 반시계 방향 90도
                transform.translate(0, width);
                transform.rotate(-Math.PI / 2);
            }
            default -> {
                // 1(정상)·알 수 없는 값은 변환 없음
            }
        }
        return transform;
    }

    /**
     * 메타데이터 없이 기록한다(JPEG은 품질 {@value #JPEG_QUALITY}·PNG는 기본). 입력 이미지는 {@link #orientAndFit}이 만든 타입이어야 한다.
     * 기록 크기가 {@code maxBytes}를 넘는 순간 기록을 멈추고 empty를 돌려준다 — 재기록은 압축 방식이 달라 입력보다 커질 수 있는데(팔레트 PNG →
     * 트루컬러 등) 끝까지 기록한 뒤 재면 그 크기만큼 힙을 먼저 쓴다.
     *
     * @throws IllegalStateException writer 없음·인코딩 실패
     */
    static Optional<byte[]> encode(BufferedImage image, ImageFormat format, long maxBytes) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        SizeLimitedImageOutputStream imageOutput = new SizeLimitedImageOutputStream(output, maxBytes);
        try {
            write(image, format, imageOutput);
            // writer가 초과 예외를 삼키고 정상 반환해도 잘린 바이트를 저장하지 않는다.
            if (imageOutput.limitExceeded()) {
                return Optional.empty();
            }
            imageOutput.close();
        } catch (IOException exception) {
            if (imageOutput.limitExceeded()) {
                return Optional.empty();
            }
            throw new IllegalStateException("재인코딩 실패", exception);
        }
        return Optional.of(output.toByteArray());
    }

    /** 이미지를 {@code imageOutput}에 기록한다(스트림은 닫지 않는다). 크기 상한 초과는 IOException으로 중단된다. */
    static void write(BufferedImage image, ImageFormat format, ImageOutputStream imageOutput) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(format == ImageFormat.JPEG ? JPEG_WRITER_NAME : PNG_WRITER_NAME);
        if (!writers.hasNext()) {
            throw new IllegalStateException("재인코딩 writer 없음: " + format);
        }
        ImageWriter writer = writers.next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (format == ImageFormat.JPEG) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(JPEG_QUALITY);
            }
            writer.setOutput(imageOutput);
            // 메타데이터 인자 null → 형식 기본 헤더만 기록(JPEG은 JFIF · EXIF·GPS·텍스트 청크 없음).
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    /**
     * 기록 위치가 상한을 넘는 쓰기를 거부하는 메모리 캐시 출력(writer가 길이 필드를 되돌아가 쓰므로 탐색 가능한 캐시가 필요하다). 거부한 쓰기는
     * 캐시에 들어가지 않아 캐시 길이는 상한을 넘지 않는다. writer가 예외를 감싸 다시 던져도 판정할 수 있게 초과 여부를 따로 기록한다.
     */
    static final class SizeLimitedImageOutputStream extends MemoryCacheImageOutputStream {

        private final long maxBytes;
        private boolean limitExceeded;

        SizeLimitedImageOutputStream(OutputStream destination, long maxBytes) {
            super(destination);
            this.maxBytes = maxBytes;
        }

        boolean limitExceeded() {
            return limitExceeded;
        }

        @Override
        public void write(int value) throws IOException {
            ensureRoom(1);
            super.write(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            ensureRoom(length);
            super.write(bytes, offset, length);
        }

        private void ensureRoom(int length) throws IOException {
            if (getStreamPosition() + length > maxBytes) {
                limitExceeded = true;
                throw new IOException("재인코딩 크기 상한 초과: max=" + maxBytes);
            }
        }
    }
}
