package com.naro.publication.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import lombok.Getter;
import lombok.Setter;
import org.springframework.web.multipart.MultipartFile;

@Setter
@Getter
public class CreatePublicationRequest {

    @NotBlank
    @Size(max = 2000)
    private String content;


    @NotNull
    private MultipartFile image;


}
