package com.zslab.mall.file.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 리뷰 사진 서브샘플링 배율(Track 106-1). 배율 s로 읽으면 긴 변은 ⌈L/s⌉이므로 "긴 변이 2048 이상 남는 가장 큰 s"여야 한다 — 경계값과
 * 넓은 구간 전수로 두 조건(⌈L/s⌉ ≥ 2048, s+1이면 2048 미만)을 확인한다.
 */
class ImageReencoderTest {

    private static final int MAX_LONG_SIDE = ImageReencoder.MAX_LONG_SIDE_PX;
    private static final int NOISE_SIDE_PX = 512;
    private static final long NOISE_SEED = 106L;
    private static final long SMALL_LIMIT_BYTES = 64L * 1024;
    private static final long LARGE_LIMIT_BYTES = 8L * 1024 * 1024;

    @Test
    @DisplayName("경계값: 2047·2048 → 1 / 4094 → 1 / 4095·4096 → 2 / 6141 → 2 / 6143 → 3 / 50,000,000 → 24425")
    void subsamplingFor_boundaries() {
        assertThat(ImageReencoder.subsamplingFor(2_047)).isEqualTo(1);
        assertThat(ImageReencoder.subsamplingFor(2_048)).isEqualTo(1);
        assertThat(ImageReencoder.subsamplingFor(4_094)).isEqualTo(1);
        assertThat(ImageReencoder.subsamplingFor(4_095)).isEqualTo(2);
        assertThat(ImageReencoder.subsamplingFor(4_096)).isEqualTo(2);
        assertThat(ImageReencoder.subsamplingFor(6_141)).isEqualTo(2);
        assertThat(ImageReencoder.subsamplingFor(6_143)).isEqualTo(3);
        assertThat(ImageReencoder.subsamplingFor(50_000_000)).isEqualTo(24_425);
    }

    @Test
    @DisplayName("전수(2048~20000): 줄여 읽은 긴 변 ⌈L/s⌉ ≥ 2048이고 배율을 1 늘리면 2048 미만(가장 큰 배율)")
    void subsamplingFor_isLargestKeepingLongSide() {
        for (int longSide = MAX_LONG_SIDE; longSide <= 20_000; longSide++) {
            int subsampling = ImageReencoder.subsamplingFor(longSide);
            assertThat(Math.ceilDiv(longSide, subsampling)).as("L=%d s=%d", longSide, subsampling).isGreaterThanOrEqualTo(MAX_LONG_SIDE);
            assertThat(Math.ceilDiv(longSide, subsampling + 1)).as("L=%d s+1=%d", longSide, subsampling + 1).isLessThan(MAX_LONG_SIDE);
        }
    }

    @Test
    @DisplayName("재기록 크기 상한(외부 검토 R2-1): 무작위 잡음(압축 불가) PNG·JPEG을 64KB 상한으로 → 중단·empty · 기록 캐시 ≤ 상한+1 / 넉넉한 상한 → 기록")
    void encode_stopsAtSizeLimit() throws IOException {
        BufferedImage noise = noise(NOISE_SIDE_PX);
        for (ImageFormat format : List.of(ImageFormat.PNG, ImageFormat.JPEG)) {
            assertThat(ImageReencoder.encode(noise, format, SMALL_LIMIT_BYTES)).as("%s 상한 초과", format).isEmpty();

            ImageReencoder.SizeLimitedImageOutputStream limited =
                    new ImageReencoder.SizeLimitedImageOutputStream(new ByteArrayOutputStream(), SMALL_LIMIT_BYTES);
            assertThatThrownBy(() -> ImageReencoder.write(noise, format, limited)).as("%s 중단", format).isInstanceOf(IOException.class);
            assertThat(limited.limitExceeded()).isTrue();
            assertThat(limited.length()).as("%s 기록 바이트", format).isLessThanOrEqualTo(SMALL_LIMIT_BYTES + 1);

            Optional<byte[]> written = ImageReencoder.encode(noise, format, LARGE_LIMIT_BYTES);
            assertThat(written).as("%s 넉넉한 상한", format).isPresent();
            assertThat(written.get().length).isGreaterThan((int) SMALL_LIMIT_BYTES);
            assertThat(ImageIO.read(new ByteArrayInputStream(written.get())).getWidth()).isEqualTo(NOISE_SIDE_PX);
        }
    }

    private static BufferedImage noise(int side) {
        Random random = new Random(NOISE_SEED);
        BufferedImage image = new BufferedImage(side, side, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side; x++) {
                image.setRGB(x, y, random.nextInt());
            }
        }
        return image;
    }
}
