package com.hardik.billing.common;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

public record ApiError(Body error) {

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Body(String code, String message, List<FieldProblem> details) {
    }

    public record FieldProblem(String field, String message) {
    }
}
