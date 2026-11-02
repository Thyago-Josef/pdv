package br.com.tjosefftech.pdv.auth;

import java.util.UUID;

public final class TokenDtos {
    private TokenDtos() {}

    // SOMENTE PARA DESENVOLVIMENTO
    public record TokenRequest(UUID tenantId, UUID lojaId) {}
    public record TokenResponse(String accessToken, String tokenType, long expiresIn) {}
}
