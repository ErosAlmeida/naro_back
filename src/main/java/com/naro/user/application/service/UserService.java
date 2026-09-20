package com.naro.user.application.service;

import com.naro.user.application.dto.UserProfileResponse;
import com.naro.user.application.dto.UserSummaryResponse;
import com.naro.user.domain.entity.User;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface UserService {

    Page<UserSummaryResponse> search(
            String query,
            User currentUser,
            Pageable pageable
    );


    UserProfileResponse getProfile(
            UUID userId
    );
}
