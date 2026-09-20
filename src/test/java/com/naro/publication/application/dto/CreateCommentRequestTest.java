package com.naro.publication.application.dto;

import com.naro.publication.domain.entity.Comment;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CreateCommentRequestTest {

    private static ValidatorFactory validatorFactory;

    private static Validator validator;


    @BeforeAll
    static void setUp() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }


    @AfterAll
    static void tearDown() {
        validatorFactory.close();
    }


    @Test
    void validContentPasses() {

        assertThat(validate("Muito boa essa publicação!")).isEmpty();
    }


    @Test
    void contentAtMaxLengthPasses() {

        String content = "a".repeat(Comment.MAX_CONTENT_LENGTH);

        assertThat(validate(content)).isEmpty();
    }


    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "   ", "\t", "\n"})
    void nullEmptyOrBlankContentFails(String content) {

        assertThat(validate(content))
                .singleElement()
                .satisfies(violation ->
                        assertThat(violation.getPropertyPath()).hasToString("content")
                );
    }


    @Test
    void contentAboveMaxLengthFails() {

        String content = "a".repeat(Comment.MAX_CONTENT_LENGTH + 1);

        assertThat(validate(content))
                .singleElement()
                .satisfies(violation ->
                        assertThat(violation.getPropertyPath()).hasToString("content")
                );
    }


    private Set<ConstraintViolation<CreateCommentRequest>> validate(String content) {
        return validator.validate(new CreateCommentRequest(content));
    }
}
