package com.zslab.mall.file.service;

import com.github.f4b6a3.ulid.UlidCreator;
import com.zslab.mall.common.exception.MalformedRequestException;
import com.zslab.mall.file.controller.response.ImageUploadResponse;
import com.zslab.mall.file.exception.UploadBusyException;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Iterator;
import java.util.Optional;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.stream.ImageInputStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 관리자 이미지 업로드 서비스(Track 77·D-166). 파일별로 (1) 크기 (2) 매직 바이트 형식 (3) 헤더 해상도 상한(D-174·전체 디코딩 전)
 * (3-1) 헤더 기준 디코딩 바이트 예산(D-230) (4) 동시 디코딩 한도({@link ImageDecodeLimiter}) 안에서 디코딩 가능 여부를 검증한 뒤
 * (파일마다 바이트를 읽기 전에 파일 처리 입장권을 먼저 얻는다 — 가득 차면 즉시 503·D-230)
 * {@code products/yyyy/MM/{ULID}.{ext}}로 저장하고 썸네일을 생성한다. 파일 하나의 실패가 다른 파일을 막지 않도록 항목별 결과로
 * 반환한다(Track 76 bulk 정합). 장수 초과·빈 요청은 요청 전체 거부(400).
 *
 * <p><b>썸네일</b>: 가로 {@value #THUMBNAIL_WIDTH}px 기준 축소(비율 유지·bilinear). 원본 가로가 그 이하이면 확대하지 않고 썸네일 URL을
 * 원본 URL로 돌려준다(파일 미생성). 썸네일 키는 {@code {base}_thumb.{thumbExt}}로 고정해 {@link #thumbnailUrlFor}가 URL만으로 역산할 수 있다.
 * 형식별 확장자는 매직 바이트 판정 결과로 고정하며 원본 파일명은 응답의 fileName 매핑에만 쓴다(경로 미사용).
 */
@Slf4j
@Service
public class ImageUploadService {

    static final int MAX_FILES_PER_REQUEST = 20;
    /**
     * 해상도 상한(D-174·픽셀 폭탄 차단): 한 변 8,000px 이하 AND 총 픽셀 {@code upload.max-image-pixels}(기본 25,000,000·D-230) 이하.
     * 헤더 판독 값으로 디코딩 전에 검사한다. 총 픽셀은 힙 512m에서 동시 디코딩 여유를 위해 4천만에서 낮췄다(D-230).
     */
    static final int MAX_IMAGE_SIDE_PX = 8_000;
    static final String IMAGE_TOO_LARGE_MESSAGE = "이미지가 너무 큽니다(최대 약 5000×5000 픽셀, 8비트 색상).";
    /** 헤더에서 디코딩 크기(밴드·샘플 비트)를 알 수 없을 때의 표식. */
    private static final long UNKNOWN_DECODED_BYTES = -1L;
    private static final int BITS_PER_BYTE = 8;
    /** 비표준 래스터를 썸네일로 축소할 때 생기는 원본 크기 INT_ARGB 복사본(픽셀당 4바이트). */
    private static final long THUMBNAIL_CONVERSION_BITS_PER_PIXEL = 32L;
    static final int THUMBNAIL_WIDTH = 400;
    /** 업로드 파일 서빙 URL 접두사(저장 키 앞에 붙어 file_path·응답 URL이 된다). 첨부 인가 서빙(D-176)이 요청 키에서 file_path를 재조립할 때 재사용한다. */
    public static final String URL_PREFIX = "/api/v1/files/";
    private static final String PRODUCT_DIRECTORY = "products";
    /** 상품 이미지로 등록 가능한 서버 발급 URL 접두사(D-174·외부 URL·임의 경로 차단). */
    public static final String PRODUCT_URL_PREFIX = URL_PREFIX + PRODUCT_DIRECTORY + "/";
    /** 셀러 업로드 하위 디렉터리(Track 90-C 검토 반영): {@code products/sellers/{sellerId}/yyyy/MM/} — 파일이 발급받은 셀러에게 귀속된다. */
    static final String SELLER_SUBDIRECTORY = "sellers";
    private static final String THUMBNAIL_SUFFIX = "_thumb";
    private static final DateTimeFormatter MONTH_DIRECTORY = DateTimeFormatter.ofPattern("yyyy/MM");

    private static final String CODE_EMPTY_FILE = "EMPTY_FILE";
    private static final String CODE_FILE_TOO_LARGE = "FILE_TOO_LARGE";
    private static final String CODE_UNSUPPORTED_FORMAT = "UNSUPPORTED_FORMAT";
    private static final String CODE_INVALID_IMAGE = "INVALID_IMAGE";
    private static final String CODE_IMAGE_TOO_LARGE = "IMAGE_TOO_LARGE";
    /**
     * 업로드 허용 형식(D-230). WebP는 손실(VP8) 디코딩이 헤더 크기와 다른 프레임 크기로 메모리를 잡아 예산을 우회할 수 있어 업로드만 중단한다.
     * {@link ImageFormat#WEBP} 정의는 이미 저장된 파일의 서빙·썸네일 URL 역산을 위해 유지한다.
     */
    private static final Set<ImageFormat> UPLOADABLE_FORMATS = EnumSet.of(ImageFormat.JPEG, ImageFormat.PNG);

    private final FileStorage fileStorage;
    /** 관리자 상품 이미지 기본 한도(요청당 20장·파일당 multipart max-file-size). 구매자 첨부는 호출부가 별도 한도를 넘긴다(D-174). */
    private final UploadLimits defaultLimits;
    private final long maxImagePixels;
    /**
     * 이미지당 디코딩 픽셀 버퍼 예산(D-230·가로×세로×밴드×샘플 바이트) — 디코더 작업 버퍼·메타데이터는 제외(동시 디코딩 1개로 힙 여유 확보).
     * 총 픽셀만으로는 16비트 RGBA 같은 고비트 형식을 막지 못한다.
     */
    private final long maxDecodeBytes;
    private final ImageDecodeLimiter imageDecodeLimiter;

    public ImageUploadService(FileStorage fileStorage, @Value("${spring.servlet.multipart.max-file-size}") long maxFileSize,
            @Value("${upload.max-image-pixels}") long maxImagePixels, @Value("${upload.max-decode-bytes}") long maxDecodeBytes,
            ImageDecodeLimiter imageDecodeLimiter) {
        this.fileStorage = fileStorage;
        this.defaultLimits = new UploadLimits(MAX_FILES_PER_REQUEST, maxFileSize);
        this.maxImagePixels = maxImagePixels;
        this.maxDecodeBytes = maxDecodeBytes;
        this.imageDecodeLimiter = imageDecodeLimiter;
    }

    /**
     * 파일 목록을 업로드한다(관리자 상품 이미지·기본 한도).
     *
     * @throws MalformedRequestException 파일 없음·{@value #MAX_FILES_PER_REQUEST}장 초과(400)
     * @throws UploadBusyException 파일 처리 입장권 소진·디코딩 대기 초과(503)
     */
    public ImageUploadResponse upload(List<MultipartFile> files) {
        return upload(files, PRODUCT_DIRECTORY, defaultLimits);
    }

    /**
     * 셀러 상품 이미지를 업로드한다(Track 90-C 검토 반영·셀러 귀속). 저장 경로는 {@code products/sellers/{sellerId}/yyyy/MM/{ULID}.{ext}}로
     * 관리자 경로({@code products/yyyy/MM/}) 아래 셀러별 하위 디렉터리에 두며, 셀러 이미지 등록은 {@link #requireSellerOwnedProductUrl}로
     * 발급 셀러와 등록 셀러의 일치를 강제한다. 형식·해상도·썸네일·한도는 관리자 업로드와 같다.
     *
     * @throws MalformedRequestException 파일 없음·{@value #MAX_FILES_PER_REQUEST}장 초과(400)
     * @throws UploadBusyException 파일 처리 입장권 소진·디코딩 대기 초과(503)
     */
    public ImageUploadResponse uploadForSeller(List<MultipartFile> files, Long sellerId) {
        return upload(files, sellerProductDirectory(sellerId), defaultLimits);
    }

    /** 셀러 업로드 저장 디렉터리·URL 접두 판정의 단일 소스({@code products/sellers/{sellerId}}). */
    static String sellerProductDirectory(Long sellerId) {
        return PRODUCT_DIRECTORY + "/" + SELLER_SUBDIRECTORY + "/" + sellerId;
    }

    /**
     * 저장 디렉터리·한도를 지정해 업로드한다(Track 81-B·클레임 첨부 {@code claims/yyyy/MM/}·D-174 경로별 한도). 형식·해상도·썸네일·응답은
     * {@link #upload(List)}와 동일하다.
     *
     * @throws MalformedRequestException 파일 없음·{@code limits.maxFiles()}장 초과(400)
     * @throws UploadBusyException 파일 처리 입장권 소진(즉시) 또는 디코딩 대기 시간 초과(503·요청 전체 실패 시 이번 요청에서 먼저 저장한
     *                             파일은 삭제)
     */
    public ImageUploadResponse upload(List<MultipartFile> files, String directory, UploadLimits limits) {
        if (files == null || files.isEmpty()) {
            throw new MalformedRequestException("업로드할 파일이 없습니다(files).");
        }
        if (files.size() > limits.maxFiles()) {
            throw new MalformedRequestException("요청당 최대 " + limits.maxFiles() + "장까지 업로드할 수 있습니다.");
        }
        List<ImageUploadResponse.Item> results = new ArrayList<>();
        try {
            for (MultipartFile file : files) {
                results.add(uploadOne(file, directory, limits.maxFileSize()));
            }
        } catch (RuntimeException requestFailure) {
            // 요청 전체가 실패(503 입장권 소진·대기 초과·저장/썸네일 오류)하면 앞서 저장한 파일의 URL은 응답되지 않아 고아가 된다 → 이번 요청 저장분을 지운다.
            results.stream().filter(item -> item.success()).forEach(item -> deleteByUrl(item.url()));
            throw requestFailure;
        }
        ImageUploadResponse response = ImageUploadResponse.of(results);
        log.info("[ImageUpload] 업로드 완료 requested={} success={} failure={}",
                files.size(), response.successCount(), response.failureCount());
        return response;
    }

    /**
     * 업로드 URL에서 썸네일 URL을 역산한다(Track 76 PUT images 대표 변경 → product.thumbnail_url 동기화용). 내부 업로드 URL이 아니면
     * (외부 URL·picsum 등) 원본을 그대로 돌려주고, 썸네일 파일이 없으면(축소 불요였던 소형 이미지) 원본 URL을 돌려준다.
     */
    public String thumbnailUrlFor(String imageUrl) {
        if (imageUrl == null || !imageUrl.startsWith(URL_PREFIX)) {
            return imageUrl;
        }
        String key = imageUrl.substring(URL_PREFIX.length());
        int dot = key.lastIndexOf('.');
        if (dot < 0) {
            return imageUrl;
        }
        String base = key.substring(0, dot);
        Optional<ImageFormat> format = ImageFormat.fromExtension(key.substring(dot + 1));
        if (format.isEmpty()) {
            return imageUrl;
        }
        String thumbnailKey = base + THUMBNAIL_SUFFIX + "." + format.get().thumbnailExtension();
        return fileStorage.exists(thumbnailKey) ? URL_PREFIX + thumbnailKey : imageUrl;
    }

    /**
     * 상품 이미지 URL이 본 서버가 발급한 상품 업로드 경로({@value #PRODUCT_URL_PREFIX} 접두사)인지 검증한다(D-174). 셀러·관리자 상품
     * 이미지 등록은 업로드 API 결과 URL만 받는다 — 외부 URL·클레임 첨부 경로·임의 경로는 400.
     *
     * @throws MalformedRequestException 서버 발급 상품 이미지 경로가 아닐 때(400)
     */
    public static void requireServerIssuedProductUrl(String imageUrl) {
        if (imageUrl == null || !imageUrl.startsWith(PRODUCT_URL_PREFIX) || imageUrl.contains("..")) {
            throw new MalformedRequestException(
                    "imageUrl은 업로드 API가 발급한 상품 이미지 경로(" + PRODUCT_URL_PREFIX + "...)만 허용합니다.");
        }
    }

    /**
     * 업로드 URL의 원본·썸네일 파일을 삭제한다(D-174 미연결 첨부 정리·행 삭제 커밋 후 호출). 내부 업로드 URL이 아니면 no-op.
     * 삭제 실패는 {@link FileStorage#delete}가 warn만 남긴다(재시도 없음·고아 파일 이월 합류).
     *
     * @return 원본 파일이 실제로 삭제됐으면 true
     */
    public boolean deleteByUrl(String imageUrl) {
        if (imageUrl == null || !imageUrl.startsWith(URL_PREFIX)) {
            return false;
        }
        String key = imageUrl.substring(URL_PREFIX.length());
        boolean deleted = fileStorage.delete(key);
        int dot = key.lastIndexOf('.');
        if (dot >= 0) {
            ImageFormat.fromExtension(key.substring(dot + 1))
                    .ifPresent(format -> fileStorage.delete(key.substring(0, dot) + THUMBNAIL_SUFFIX + "." + format.thumbnailExtension()));
        }
        return deleted;
    }

    /**
     * 셀러 상품 이미지 URL이 본 서버가 <b>해당 셀러에게</b> 발급한 경로({@code /api/v1/files/products/sellers/{sellerId}/...})이고 <b>실제로
     * 저장된 파일</b>인지 검증한다(Track 90-C 검토 반영). 서버 발급 접두 검증({@link #requireServerIssuedProductUrl})에 더해 경로의 셀러 id가
     * 등록 셀러와 같아야 하고, 접두를 뗀 저장 키가 {@link FileStorage#exists}여야 한다 — 타 셀러가 발급받은 URL·관리자 경로
     * ({@code products/yyyy/MM/})·본인 네임스페이스 안의 미업로드 URL의 신규 등록은 400. 원본 URL과 썸네일 URL({@code _thumb}) 모두 실제 저장
     * 키이므로 어느 쪽을 보내도 통과한다(소형 이미지는 썸네일 미생성이라 원본 URL이 온다). 기존 행의 URL을 그대로 되돌리는 편집(관리자가
     * 붙인 이미지 보존)은 호출부가 이 검증을 건너뛴다. 저장소 조회가 필요해 인스턴스 메서드다(관리자용 static 검증은 무변경).
     *
     * @throws MalformedRequestException 해당 셀러에게 발급된 상품 이미지 경로가 아니거나 저장 파일이 없을 때(400)
     */
    public void requireSellerOwnedProductUrl(String imageUrl, Long sellerId) {
        requireServerIssuedProductUrl(imageUrl);
        String sellerPrefix = URL_PREFIX + sellerProductDirectory(sellerId) + "/";
        if (!imageUrl.startsWith(sellerPrefix)) {
            throw new MalformedRequestException(
                    "imageUrl은 본인이 업로드 API로 발급받은 상품 이미지 경로(" + sellerPrefix + "...)만 허용합니다.");
        }
        if (!fileStorage.exists(imageUrl.substring(URL_PREFIX.length()))) {
            throw new MalformedRequestException("imageUrl에 해당하는 업로드 파일이 없습니다: " + imageUrl);
        }
    }

    /**
     * 파일 하나를 처리한다. 파일 바이트를 힙에 올리기 전에 입장권을 얻고(가득 차면 즉시 503) 모든 결과·예외에서 반납한다 — 디코딩 차례를
     * 기다리는 요청마다 파일 바이트를 쥐고 있어 대기자 수에 비례해 힙이 늘던 문제를 막는다(D-230). 획득 순서는 입장권 → 디코딩 차례 고정.
     *
     * @throws UploadBusyException 입장권 소진(즉시) 또는 디코딩 차례 대기 초과(503)
     */
    private ImageUploadResponse.Item uploadOne(MultipartFile file, String directory, long maxFileSize) {
        imageDecodeLimiter.admitFile();
        try {
            return inspectAndStore(file, directory, maxFileSize);
        } finally {
            imageDecodeLimiter.releaseFile();
        }
    }

    private ImageUploadResponse.Item inspectAndStore(MultipartFile file, String directory, long maxFileSize) {
        String fileName = file.getOriginalFilename();
        if (file.isEmpty()) {
            return ImageUploadResponse.Item.failure(fileName, CODE_EMPTY_FILE, "빈 파일입니다.");
        }
        if (file.getSize() > maxFileSize) {
            return ImageUploadResponse.Item.failure(fileName, CODE_FILE_TOO_LARGE,
                    "파일당 최대 " + maxFileSize + "바이트까지 허용합니다: size=" + file.getSize());
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException exception) {
            log.warn("[ImageUpload] 파일 읽기 실패 fileName={}: {}", fileName, exception.getMessage());
            return ImageUploadResponse.Item.failure(fileName, CODE_INVALID_IMAGE, "파일을 읽을 수 없습니다.");
        }
        Optional<ImageFormat> detected = ImageFormat.detect(content);
        if (detected.isEmpty() || !UPLOADABLE_FORMATS.contains(detected.get())) {
            return ImageUploadResponse.Item.failure(fileName, CODE_UNSUPPORTED_FORMAT, "jpg·png만 허용합니다(매직 바이트 불일치).");
        }
        ImageFormat format = detected.get();
        // D-174: 전체 디코딩 전에 헤더 해상도만 읽어 픽셀 폭탄(작은 파일·거대 해상도)을 차단한다. 헤더를 읽을 수 없으면 기존대로 거부.
        Optional<ImageHeader> header = readHeader(content);
        if (header.isEmpty()) {
            return ImageUploadResponse.Item.failure(fileName, CODE_INVALID_IMAGE, "이미지 헤더를 읽을 수 없습니다.");
        }
        int headerWidth = header.get().width();
        int headerHeight = header.get().height();
        if (headerWidth > MAX_IMAGE_SIDE_PX || headerHeight > MAX_IMAGE_SIDE_PX
                || (long) headerWidth * headerHeight > maxImagePixels) {
            log.warn("[ImageUpload] 해상도 상한 초과 거부(디코딩 전) {}x{}", headerWidth, headerHeight);
            return ImageUploadResponse.Item.failure(fileName, CODE_IMAGE_TOO_LARGE, IMAGE_TOO_LARGE_MESSAGE);
        }
        // D-230: 디코딩 결과 크기(밴드·샘플 비트 반영)를 헤더 단계에서 예산과 비교한다. 알 수 없으면 디코딩하지 않고 거부(fail-closed).
        long decodedBytes = header.get().decodedBytes();
        if (decodedBytes == UNKNOWN_DECODED_BYTES) {
            return ImageUploadResponse.Item.failure(fileName, CODE_INVALID_IMAGE, "이미지 헤더를 읽을 수 없습니다.");
        }
        if (decodedBytes > maxDecodeBytes) {
            log.warn("[ImageUpload] 디코딩 예산 초과 거부(디코딩 전) {}x{} decodedBytes={}", headerWidth, headerHeight, decodedBytes);
            return ImageUploadResponse.Item.failure(fileName, CODE_IMAGE_TOO_LARGE, IMAGE_TOO_LARGE_MESSAGE);
        }
        // 디코딩한 이미지는 썸네일 생성이 끝날 때까지 힙에 남으므로 그 구간 전체를 동시 디코딩 차례로 묶는다.
        imageDecodeLimiter.acquire();
        try {
            return decodeAndStore(fileName, content, format, directory);
        } finally {
            imageDecodeLimiter.release();
        }
    }

    private ImageUploadResponse.Item decodeAndStore(String fileName, byte[] content, ImageFormat format, String directory) {
        BufferedImage image = decode(content);
        if (image == null) {
            return ImageUploadResponse.Item.failure(fileName, CODE_INVALID_IMAGE, "이미지를 디코딩할 수 없습니다.");
        }

        String base = directory + "/" + LocalDate.now().format(MONTH_DIRECTORY) + "/" + UlidCreator.getUlid().toString();
        String originalKey = base + "." + format.extension();
        fileStorage.store(originalKey, content);
        String url = URL_PREFIX + originalKey;

        String thumbnailUrl = url;
        if (image.getWidth() > THUMBNAIL_WIDTH) {
            String thumbnailKey = base + THUMBNAIL_SUFFIX + "." + format.thumbnailExtension();
            fileStorage.store(thumbnailKey, encodeThumbnail(image, format));
            thumbnailUrl = URL_PREFIX + thumbnailKey;
        }
        log.info("[ImageUpload] 저장 key={} {}x{} size={} thumb={}", originalKey, image.getWidth(), image.getHeight(),
                content.length, !thumbnailUrl.equals(url));
        return ImageUploadResponse.Item.success(fileName, url, thumbnailUrl, image.getWidth(), image.getHeight(), content.length);
    }

    /** 헤더 판독 결과. decodedBytes는 디코딩 시 픽셀 버퍼 크기 추정(알 수 없으면 {@link #UNKNOWN_DECODED_BYTES}). */
    private record ImageHeader(int width, int height, long decodedBytes) {}

    /**
     * 헤더만 판독해 가로·세로와 디코딩 바이트 추정을 돌려준다(픽셀 데이터 미디코딩). reader가 없거나 헤더 파손이면 empty. 가로·세로는
     * 읽혔는데 형식(밴드·샘플 비트)을 알 수 없으면 decodedBytes만 UNKNOWN이다 — 해상도 상한 판정은 그대로 먼저 적용된다.
     */
    private static Optional<ImageHeader> readHeader(byte[] content) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            if (input == null) {
                return Optional.empty();
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return Optional.empty();
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                return Optional.of(new ImageHeader(width, height, estimateDecodedBytes(reader, width, height)));
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException exception) {
            // 헤더 파손·reader 내부 오류는 "읽을 수 없는 이미지"로 취급해 파일별 실패로 돌린다(요청 전체 실패 아님).
            log.warn("[ImageUpload] 헤더 판독 실패: {}", exception.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 가로 × 세로 × 픽셀당 비트 ÷ 8(올림)로 디코딩 픽셀 버퍼를 추정한다(디코더 작업 버퍼·메타데이터는 제외 — 동시 디코딩 1개로 힙 여유
     * 확보 · long·overflow 시 예외 → UNKNOWN). 픽셀당 비트는 raw type과
     * {@code ImageIO.read}가 실제로 할당하는 첫 지원 타입 중 큰 쪽이다 — PNG는 raw type이 마지막 타입이라 tRNS가 있으면 첫 타입(알파 밴드
     * 추가)보다 작다. 첫 타입이 비표준 래스터(TYPE_CUSTOM·TYPE_BYTE_BINARY — 16비트·저비트 등)면 썸네일 축소(bilinear) 때 원본 크기
     * INT_ARGB 복사본이 생기므로 픽셀당 4바이트를 더한다. 타입을 하나도 알 수 없거나 메타데이터를 읽지 못하면 UNKNOWN(디코딩하지 않음).
     */
    private static long estimateDecodedBytes(ImageReader reader, int width, int height) {
        try {
            Iterator<ImageTypeSpecifier> types = reader.getImageTypes(0);
            ImageTypeSpecifier allocated = types.hasNext() ? types.next() : null;
            ImageTypeSpecifier raw = reader.getRawImageType(0);
            if (allocated == null && raw == null) {
                return UNKNOWN_DECODED_BYTES;
            }
            long bitsPerPixel = Math.max(bitsPerPixel(raw), bitsPerPixel(allocated));
            if (allocated == null || needsFullSizeConversion(allocated)) {
                bitsPerPixel += THUMBNAIL_CONVERSION_BITS_PER_PIXEL;
            }
            long totalBits = Math.multiplyExact(Math.multiplyExact((long) width, (long) height), bitsPerPixel);
            return Math.ceilDiv(totalBits, BITS_PER_BYTE);
        } catch (IOException | RuntimeException exception) {
            // 형식 판독 실패는 디코딩 크기를 모르는 것 → 호출부가 디코딩 없이 거부한다(요청 전체 실패 아님).
            log.warn("[ImageUpload] 디코딩 크기 판독 실패: {}", exception.getMessage());
            return UNKNOWN_DECODED_BYTES;
        }
    }

    private static long bitsPerPixel(ImageTypeSpecifier type) {
        if (type == null) {
            return 0;
        }
        long bits = 0;
        for (int sampleBits : type.getSampleModel().getSampleSize()) {
            bits += sampleBits;
        }
        return bits;
    }

    private static boolean needsFullSizeConversion(ImageTypeSpecifier type) {
        int bufferedImageType = type.getBufferedImageType();
        return bufferedImageType == BufferedImage.TYPE_CUSTOM || bufferedImageType == BufferedImage.TYPE_BYTE_BINARY;
    }

    private static BufferedImage decode(byte[] content) {
        try {
            return ImageIO.read(new ByteArrayInputStream(content));
        } catch (IOException exception) {
            log.warn("[ImageUpload] 디코딩 실패: {}", exception.getMessage());
            return null;
        }
    }

    /** 가로 THUMBNAIL_WIDTH 기준 비율 축소. jpg는 알파 없는 RGB로, png(webp 포함)는 ARGB로 그린다. */
    static byte[] encodeThumbnail(BufferedImage image, ImageFormat format) {
        int height = Math.max(1, (int) Math.round((double) image.getHeight() * THUMBNAIL_WIDTH / image.getWidth()));
        int imageType = format == ImageFormat.JPEG ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB;
        BufferedImage thumbnail = new BufferedImage(THUMBNAIL_WIDTH, height, imageType);
        Graphics2D graphics = thumbnail.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(image, 0, 0, THUMBNAIL_WIDTH, height, null);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(thumbnail, format.thumbnailWriterName(), output)) {
                throw new IllegalStateException("썸네일 writer 없음: " + format.thumbnailWriterName());
            }
        } catch (IOException exception) {
            throw new IllegalStateException("썸네일 인코딩 실패", exception);
        }
        return output.toByteArray();
    }
}
