package com.naro.user.application.service;

import com.naro.exception.BadRequestException;
import com.naro.exception.NotFoundException;
import com.naro.user.application.dto.UserProfileResponse;
import com.naro.user.application.dto.UserSummaryResponse;
import com.naro.user.application.mapper.UserMapper;
import com.naro.user.domain.entity.User;
import com.naro.user.domain.repository.UserRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

@Service
public class UserServiceImpl implements UserService {

    static final int MIN_QUERY_LENGTH = 2;

    static final int MAX_QUERY_LENGTH = 50;


    private final UserRepository userRepository;

    private final UserMapper userMapper;


    public UserServiceImpl(
            UserRepository userRepository,
            UserMapper userMapper
    ) {
        this.userRepository = userRepository;
        this.userMapper = userMapper;
    }


    @Override
    @Transactional(readOnly = true)
    public Page<UserSummaryResponse> search(
            String query,
            User currentUser,
            Pageable pageable
    ) {

        String normalized =
                query == null
                        ? ""
                        : query.trim();


        if (
                normalized.length() < MIN_QUERY_LENGTH
                        || normalized.length() > MAX_QUERY_LENGTH
        ) {

            throw new BadRequestException(
                    "Search query must have between "
                            + MIN_QUERY_LENGTH
                            + " and "
                            + MAX_QUERY_LENGTH
                            + " characters"
            );
        }


        return userRepository
                .search(
                        containsPattern(normalized),
                        currentUser.getId(),
                        pageable
                )
                .map(
                        userMapper::toSummaryResponse
                );
    }


    @Override
    @Transactional(readOnly = true)
    public UserProfileResponse getProfile(
            UUID userId
    ) {

        return userRepository
                .findById(userId)
                .map(
                        userMapper::toProfileResponse
                )
                .orElseThrow(
                        () -> new NotFoundException(
                                "User not found"
                        )
                );
    }


    /*
     * Escapa os curingas do LIKE para que "%" ou "_"
     * digitados pelo usuário sejam tratados como texto.
     */
    static String containsPattern(String query) {

        String escaped =
                query
                        .toLowerCase(Locale.ROOT)
                        .replace("\\", "\\\\")
                        .replace("%", "\\%")
                        .replace("_", "\\_");

        return "%" + escaped + "%";
    }
}
