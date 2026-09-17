package com.zslab.mall.file.service;

import com.github.f4b6a3.ulid.UlidCreator;
import com.zslab.mall.common.exception.MalformedRequestException;
import com.zslab.mall.file.controller.response.ImageUploadResponse;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Iterator;
import java.util.Optional;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 관리자 이미지 업로드 서비스(Track 77·D-166). 파일별로 (1) 크기 (2) 매직 바이트 형식 (3) 헤더 해상도 상한(D-174·전체 디코딩 전)
 * (4) 디코딩 가능 여부를 검증한 뒤
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
    /** 해상도 상한(D-174·픽셀 폭탄 차단): 한 변 8,000px 이하 AND 총 픽셀 40,000,000 이하. 헤더 판독 값으로 디코딩 전에 검사한다. */
    static final int MAX_IMAGE_SIDE_PX = 8_000;
    static final long MAX_IMAGE_PIXELS = 40_000_000L;
    static final int THUMBNAIL_WIDTH = 400;
    /** 업로드 파일 서빙 URL 접두사(저장 키 앞에 붙어 file_path·응답 URL이 된다). 첨부 인가 서빙(D-176)이 요청 키에서 file_path를 재조립할 때 재사용한다. */
    public static final String URL_PREFIX = "/api/v1/files/";
    private static final String PRODUCT_DIRECTORY = "products";
    /** 상품 이미지로 등록 가능한 서버 발급 URL 접두사(D-174·외부 URL·임의 경로 차단). */
    public static final String PRODUCT_URL_PREFIX = URL_PREFIX + PRODUCT_DIRECTORY + "/";
    private static final String THUMBNAIL_SUFFIX = "_thumb";
    private static final DateTimeFormatter MONTH_DIRECTORY = DateTimeFormatter.ofPattern("yyyy/MM");

    private static final String CODE_EMPTY_FILE = "EMPTY_FILE";
    private static final String CODE_FILE_TOO_LARGE = "FILE_TOO_LARGE";
    private static final String CODE_UNSUPPORTED_FORMAT = "UNSUPPORTED_FORMAT";
    private static final String CODE_INVALID_IMAGE = "INVALID_IMAGE";
    private static final String CODE_IMAGE_TOO_LARGE = "IMAGE_TOO_LARGE";

    private final FileStorage fileStorage;
    /** 관리자 상품 이미지 기본 한도(요청당 20장·파일당 multipart max-file-size). 구매자 첨부는 호출부가 별도 한도를 넘긴다(D-174). */
    private final UploadLimits defaultLimits;

    public ImageUploadService(FileStorage fileStorage, @Value("${spring.servlet.multipart.max-file-size}") long maxFileSize) {
        this.fileStorage = fileStorage;
        this.defaultLimits = new UploadLimits(MAX_FILES_PER_REQUEST, maxFileSize);
    }

    /**
     * 파일 목록을 업로드한다(관리자 상품 이미지·기본 한도).
     *
     * @throws MalformedRequestException 파일 없음·{@value #MAX_FILES_PER_REQUEST}장 초과(400)
     */
    public ImageUploadResponse upload(List<MultipartFile> files) {
        return upload(files, PRODUCT_DIRECTORY, defaultLimits);
    }

    /**
     * 저장 디렉터리·한도를 지정해 업로드한다(Track 81-B·클레임 첨부 {@code claims/yyyy/MM/}·D-174 경로별 한도). 형식·해상도·썸네일·응답은
     * {@link #upload(List)}와 동일하다.
     *
     * @throws MalformedRequestException 파일 없음·{@code limits.maxFiles()}장 초과(400)
     */
    public ImageUploadResponse upload(List<MultipartFile> files, String directory, UploadLimits limits) {
        if (files == null || files.isEmpty()) {
            throw new MalformedRequestException("업로드할 파일이 없습니다(files).");
        }
        if (files.size() > limits.maxFiles()) {
            throw new MalformedRequestException("요청당 최대 " + limits.maxFiles() + "장까지 업로드할 수 있습니다.");
        }
        List<ImageUploadResponse.Item> results = new ArrayList<>();
        for (MultipartFile file : files) {
            results.add(uploadOne(file, directory, limits.maxFileSize()));
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

    private ImageUploadResponse.Item uploadOne(MultipartFile file, String directory, long maxFileSize) {
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
        if (detected.isEmpty()) {
            return ImageUploadResponse.Item.failure(fileName, CODE_UNSUPPORTED_FORMAT,
                    "jpg·png·webp만 허용합니다(매직 바이트 불일치).");
        }
        ImageFormat format = detected.get();
        // D-174: 전체 디코딩 전에 헤더 해상도만 읽어 픽셀 폭탄(작은 파일·거대 해상도)을 차단한다. 헤더를 읽을 수 없으면 기존대로 거부.
        Optional<int[]> dimensions = readDimensions(content);
        if (dimensions.isEmpty()) {
            return ImageUploadResponse.Item.failure(fileName, CODE_INVALID_IMAGE, "이미지 헤더를 읽을 수 없습니다.");
        }
        int headerWidth = dimensions.get()[0];
        int headerHeight = dimensions.get()[1];
        if (headerWidth > MAX_IMAGE_SIDE_PX || headerHeight > MAX_IMAGE_SIDE_PX
                || (long) headerWidth * headerHeight > MAX_IMAGE_PIXELS) {
            return ImageUploadResponse.Item.failure(fileName, CODE_IMAGE_TOO_LARGE,
                    "이미지 해상도가 상한을 초과합니다(한 변 " + MAX_IMAGE_SIDE_PX + "px·총 " + MAX_IMAGE_PIXELS + "px 이하): "
                            + headerWidth + "x" + headerHeight);
        }
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

    /** 헤더만 판독해 [width, height]를 돌려준다(픽셀 데이터 미디코딩). reader가 없거나 헤더 파손이면 empty. */
    private static Optional<int[]> readDimensions(byte[] content) {
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
                return Optional.of(new int[] {reader.getWidth(0), reader.getHeight(0)});
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException exception) {
            // 헤더 파손·reader 내부 오류는 "읽을 수 없는 이미지"로 취급해 파일별 실패로 돌린다(요청 전체 실패 아님).
            log.warn("[ImageUpload] 헤더 판독 실패: {}", exception.getMessage());
            return Optional.empty();
        }
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
