package com.naro.storage.service;

import com.naro.exception.BadRequestException;
import com.naro.storage.config.S3StorageProperties;
import com.naro.storage.exception.StorageException;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class S3ObjectStorageService
        implements ObjectStorageService {

    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp"
    );

    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/jpeg", ".jpg",
            "image/png", ".png",
            "image/webp", ".webp"
    );


    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final S3StorageProperties properties;


    public S3ObjectStorageService(
            S3Client s3Client,
            S3Presigner s3Presigner,
            S3StorageProperties properties
    ) {
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
        this.properties = properties;
    }


    @Override
    public String upload(
            String directory,
            MultipartFile file
    ) {

        validateImage(file);

        String extension = EXTENSIONS.get(
                file.getContentType()
        );

        String objectKey =
                directory
                        + "/"
                        + UUID.randomUUID()
                        + extension;

        try {

            PutObjectRequest request =
                    PutObjectRequest.builder()
                            .bucket(properties.bucket())
                            .key(objectKey)
                            .contentType(file.getContentType())
                            .build();

            s3Client.putObject(
                    request,
                    RequestBody.fromInputStream(
                            file.getInputStream(),
                            file.getSize()
                    )
            );

            return objectKey;

        } catch (IOException exception) {

            throw new StorageException(
                    "Could not upload image",
                    exception
            );
        }
    }


    @Override
    public String createReadUrl(
            String objectKey
    ) {

        GetObjectRequest objectRequest =
                GetObjectRequest.builder()
                        .bucket(properties.bucket())
                        .key(objectKey)
                        .build();

        GetObjectPresignRequest presignRequest =
                GetObjectPresignRequest.builder()
                        .signatureDuration(
                                properties.presignedUrlDuration()
                        )
                        .getObjectRequest(objectRequest)
                        .build();

        return s3Presigner
                .presignGetObject(presignRequest)
                .url()
                .toString();
    }


    @Override
    public void delete(
            String objectKey
    ) {

        DeleteObjectRequest request =
                DeleteObjectRequest.builder()
                        .bucket(properties.bucket())
                        .key(objectKey)
                        .build();

        s3Client.deleteObject(request);
    }


    private void validateImage(
            MultipartFile file
    ) {

        if (file == null || file.isEmpty()) {

            throw new BadRequestException(
                    "Image is required"
            );
        }

        String contentType = file.getContentType();

        if (
                contentType == null
                        || !ALLOWED_IMAGE_TYPES.contains(contentType)
        ) {

            throw new BadRequestException(
                    "Only JPEG, PNG and WEBP images are allowed"
            );
        }
    }
}