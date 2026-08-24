package com.aitp.orenda.auth.dto;

public record ApiError(
        int status,
        String code,
        String message
) {
    public static ApiError of(int status, String code, String message) {
        return new ApiError(status, code, message);
    }
}