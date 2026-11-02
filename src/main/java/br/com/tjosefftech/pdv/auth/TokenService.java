package br.com.tjosefftech.pdv.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class TokenService {

    private final JwtEncoder jwtEncoder;
    private final String issuer;

    public TokenService(JwtEncoder jwtEncoder, @Value("${jwt.issuer}") String issuer) {
        this.jwtEncoder = jwtEncoder;
        this.issuer = issuer;
    }

    // SOMENTE PARA DESENVOLVIMENTO - Em producao, token vem de login real (usuario/senha),
    // com tenant_id obtido do cadastro do usuario no banco, nunca enviado pelo cliente.
    public String gerarToken(UUID tenantId, String perfil, List<UUID> lojas, UUID lojaId) {
        Instant now = Instant.now();
        Instant exp = now.plus(1, ChronoUnit.HOURS);

        JwtClaimsSet.Builder claimsBuilder = JwtClaimsSet.builder()
                .issuer(issuer)
                .issuedAt(now)
                .expiresAt(exp)
                .subject(tenantId.toString())
                .claim("tenant_id", tenantId.toString())
                .claim("perfil", perfil);

        if (lojas != null && !lojas.isEmpty()) {
            List<String> lojasClaim = new ArrayList<>();
            for (UUID l : lojas) {
                lojasClaim.add(l.toString());
            }
            claimsBuilder.claim("lojas", lojasClaim);
        }

        if ("OPERADOR".equalsIgnoreCase(perfil)) {
            claimsBuilder.claim("loja_id", lojaId != null ? lojaId.toString() : null);
        }

        JwtClaimsSet claims = claimsBuilder.build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return this.jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public String gerarToken(UUID tenantId) {
        return gerarToken(tenantId, "OPERADOR", java.util.Collections.emptyList(), null);
    }

    public long getExpiresIn() {
        return 3600L;
    }
}
