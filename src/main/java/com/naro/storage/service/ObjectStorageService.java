package com.naro.storage.service;

import org.springframework.web.multipart.MultipartFile;

public interface ObjectStorageService {

    String upload(
            String directory,
            MultipartFile file
    );

    String createReadUrl(
            String objectKey
    );

    void delete(
            String objectKey
    );
}