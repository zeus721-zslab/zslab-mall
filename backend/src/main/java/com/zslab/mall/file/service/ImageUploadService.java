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
import java.util.Optional;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 관리자 이미지 업로드 서비스(Track 77·D-166). 파일별로 (1) 크기 (2) 매직 바이트 형식 (3) 디코딩 가능 여부를 검증한 뒤
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
    static final int THUMBNAIL_WIDTH = 400;
    static final String URL_PREFIX = "/api/v1/files/";
    private static final String PRODUCT_DIRECTORY = "products";
    private static final String THUMBNAIL_SUFFIX = "_thumb";
    private static final DateTimeFormatter MONTH_DIRECTORY = DateTimeFormatter.ofPattern("yyyy/MM");

    private static final String CODE_EMPTY_FILE = "EMPTY_FILE";
    private static final String CODE_FILE_TOO_LARGE = "FILE_TOO_LARGE";
    private static final String CODE_UNSUPPORTED_FORMAT = "UNSUPPORTED_FORMAT";
    private static final String CODE_INVALID_IMAGE = "INVALID_IMAGE";

    private final FileStorage fileStorage;
    private final long maxFileSize;

    public ImageUploadService(FileStorage fileStorage, @Value("${spring.servlet.multipart.max-file-size}") long maxFileSize) {
        this.fileStorage = fileStorage;
        this.maxFileSize = maxFileSize;
    }

    /**
     * 파일 목록을 업로드한다.
     *
     * @throws MalformedRequestException 파일 없음·{@value #MAX_FILES_PER_REQUEST}장 초과(400)
     */
    public ImageUploadResponse upload(List<MultipartFile> files) {
        return upload(files, PRODUCT_DIRECTORY);
    }

    /**
     * 저장 디렉터리를 지정해 업로드한다(Track 81-B·클레임 첨부 {@code claims/yyyy/MM/}). 검증·썸네일·응답은 {@link #upload(List)}와 동일하다.
     *
     * @throws MalformedRequestException 파일 없음·{@value #MAX_FILES_PER_REQUEST}장 초과(400)
     */
    public ImageUploadResponse upload(List<MultipartFile> files, String directory) {
        if (files == null || files.isEmpty()) {
            throw new MalformedRequestException("업로드할 파일이 없습니다(files).");
        }
        if (files.size() > MAX_FILES_PER_REQUEST) {
            throw new MalformedRequestException("요청당 최대 " + MAX_FILES_PER_REQUEST + "장까지 업로드할 수 있습니다.");
        }
        List<ImageUploadResponse.Item> results = new ArrayList<>();
        for (MultipartFile file : files) {
            results.add(uploadOne(file, directory));
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

    private ImageUploadResponse.Item uploadOne(MultipartFile file, String directory) {
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
