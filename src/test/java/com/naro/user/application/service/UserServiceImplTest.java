package com.naro.user.application.service;

import com.naro.exception.BadRequestException;
import com.naro.exception.NotFoundException;
import com.naro.user.application.dto.UserSummaryResponse;
import com.naro.user.application.mapper.UserMapperImpl;
import com.naro.user.domain.entity.User;
import com.naro.user.domain.repository.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private UserRepository userRepository;

    private UserServiceImpl userService;

    private final User currentUser = user("leonardo");


    @BeforeEach
    void setUp() {
        userService = new UserServiceImpl(userRepository, new UserMapperImpl());
    }


    @Test
    void searchExcludesCurrentUserAndUsesCaseInsensitiveContainsPattern() {

        Pageable pageable = PageRequest.of(0, 20);
        User maria = user("maria");

        when(userRepository.search("%mar%", currentUser.getId(), pageable))
                .thenReturn(new PageImpl<>(List.of(maria), pageable, 1));

        Page<UserSummaryResponse> page = userService.search("  MAR ", currentUser, pageable);

        assertThat(page.getContent())
                .singleElement()
                .satisfies(summary -> {
                    assertThat(summary.id()).isEqualTo(maria.getId());
                    assertThat(summary.username()).isEqualTo("maria");
                });
    }


    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "a", "  a  "})
    void searchWithTooShortQueryIsRejected(String query) {

        assertThatThrownBy(() -> userService.search(query, currentUser, PageRequest.of(0, 20)))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(userRepository);
    }


    @Test
    void searchWithTooLongQueryIsRejected() {

        assertThatThrownBy(() -> userService.search("a".repeat(51), currentUser, PageRequest.of(0, 20)))
                .isInstanceOf(BadRequestException.class);
    }


    @Test
    void likeWildcardsTypedByUserAreEscaped() {

        assertThat(UserServiceImpl.containsPattern("100%_real\\"))
                .isEqualTo("%100\\%\\_real\\\\%");
    }


    @Test
    void getProfileOfUnknownUserThrowsNotFound() {

        UUID unknown = UUID.randomUUID();

        when(userRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getProfile(unknown))
                .isInstanceOf(NotFoundException.class);
    }


    private static User user(String username) {

        User user = new User(UUID.randomUUID().toString(), username, username + "@naro.dev", null, null);

        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());

        return user;
    }
}
