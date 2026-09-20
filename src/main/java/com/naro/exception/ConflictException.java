package com.naro.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class ConflictException extends ErrorResponseException {

    public ConflictException(String message) {
        super(HttpStatus.CONFLICT, createProblemDetail(message), null);
    }

    private static ProblemDetail createProblemDetail(String message) {
        ProblemDetail problemDetail =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.CONFLICT,
                        message
                );

        problemDetail.setTitle("Conflict");

        return problemDetail;
    }
}
