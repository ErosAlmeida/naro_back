package com.naro.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class NotFoundException extends ErrorResponseException {

    public NotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, createProblemDetail(message), null);
    }

    private static ProblemDetail createProblemDetail(String message) {
        ProblemDetail problemDetail =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.NOT_FOUND,
                        message
                );

        problemDetail.setTitle("Resource not found");

        return problemDetail;
    }
}
