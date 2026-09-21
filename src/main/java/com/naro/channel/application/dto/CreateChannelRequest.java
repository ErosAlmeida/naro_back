package com.naro.channel.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.multipart.MultipartFile;

public record CreateChannelRequest(
        @NotBlank
        @Size(max = 100)
        String name,

        @NotBlank
        @Size(max = 100)
        String slug,

        @Size(max = 500)
        String description,

        MultipartFile profile_image,

        MultipartFile banner_image

) {


}

