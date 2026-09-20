package com.naro.storage.service;

import com.naro.exception.BadRequestException;
import com.naro.storage.config.S3StorageProperties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class S3ObjectStorageServiceTest {

    @Mock
    private S3Client s3Client;

    @Mock
    private S3Presigner s3Presigner;

    private S3ObjectStorageService storageService;


    @BeforeEach
    void setUp() {
        storageService = new S3ObjectStorageService(
                s3Client,
                s3Presigner,
                new S3StorageProperties(
                        URI.create("http://localhost:9000"),
                        "us-east-1",
                        "naro",
                        "secret",
                        "naro-publications",
                        Duration.ofMinutes(30)
                )
        );
    }


    @Test
    void uploadStoresImageUnderDirectoryWithExtensionFromContentType() {

        MockMultipartFile image =
                new MockMultipartFile("image", "photo.png", "image/png", new byte[]{1, 2, 3});

        String objectKey = storageService.upload("publications", image);

        assertThat(objectKey).startsWith("publications/").endsWith(".png");

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(captor.capture(), any(RequestBody.class));

        assertThat(captor.getValue().bucket()).isEqualTo("naro-publications");
        assertThat(captor.getValue().key()).isEqualTo(objectKey);
        assertThat(captor.getValue().contentType()).isEqualTo("image/png");
    }


    @Test
    void uploadWithEmptyFileThrowsBadRequest() {

        MockMultipartFile empty =
                new MockMultipartFile("image", "photo.png", "image/png", new byte[0]);

        assertThatThrownBy(() -> storageService.upload("publications", empty))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Image is required");

        verifyNoInteractions(s3Client);
    }


    @Test
    void uploadWithUnsupportedContentTypeThrowsBadRequest() {

        MockMultipartFile gif =
                new MockMultipartFile("image", "anim.gif", "image/gif", new byte[]{1});

        assertThatThrownBy(() -> storageService.upload("publications", gif))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Only JPEG, PNG and WEBP images are allowed");

        verifyNoInteractions(s3Client);
    }
}
