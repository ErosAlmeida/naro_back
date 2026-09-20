package com.naro.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class ForbiddenException extends ErrorResponseException {

    public ForbiddenException(String message) {
        super(HttpStatus.FORBIDDEN, createProblemDetail(message), null);
    }

    private static ProblemDetail createProblemDetail(String message) {
        ProblemDetail problemDetail =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.FORBIDDEN,
                        message
                );

        problemDetail.setTitle("Forbidden");

        return problemDetail;
    }
}
