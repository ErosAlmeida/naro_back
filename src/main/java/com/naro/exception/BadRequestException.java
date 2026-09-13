package com.naro.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class BadRequestException extends ErrorResponseException {

    public BadRequestException(String message) {
        super(HttpStatus.BAD_REQUEST, createProblemDetail(message), null);
    }

    private static ProblemDetail createProblemDetail(String message) {
        ProblemDetail problemDetail =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.BAD_REQUEST,
                        message
                );

        problemDetail.setTitle("Bad request");

        return problemDetail;
    }
}
