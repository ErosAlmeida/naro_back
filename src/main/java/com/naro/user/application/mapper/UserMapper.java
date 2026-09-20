package com.naro.user.application.mapper;

import com.naro.user.application.dto.CurrentUserResponse;
import com.naro.user.application.dto.UserProfileResponse;
import com.naro.user.application.dto.UserSummaryResponse;
import com.naro.user.domain.entity.User;

import org.mapstruct.Mapper;

@Mapper
public interface UserMapper {

    CurrentUserResponse toCurrentUserResponse(
            User user
    );


    UserSummaryResponse toSummaryResponse(
            User user
    );


    UserProfileResponse toProfileResponse(
            User user
    );
}
