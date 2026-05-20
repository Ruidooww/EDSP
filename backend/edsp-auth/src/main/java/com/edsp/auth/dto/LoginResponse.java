package com.edsp.auth.dto;

public record LoginResponse(String accessToken, String tokenType, long expiresInSeconds) {
}
