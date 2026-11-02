package br.com.tjosefftech.pdv.auth;

import java.util.List;
import java.util.UUID;

public final class TokenDtos {
    private TokenDtos() {}

    // SOMENTE PARA DESENVOLVIMENTO
    public record TokenRequest(UUID tenantId, String perfil, List<UUID> lojas, UUID lojaId) {}
    public record TokenResponse(String accessToken, String tokenType, long expiresIn) {}
}
