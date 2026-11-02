package br.com.tjosefftech.pdv.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
public class TenantContextFilter extends OncePerRequestFilter {

    private final JwtDecoder jwtDecoder;

    public TenantContextFilter(JwtDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            String header = request.getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                String token = header.substring(7);
                try {
                    Jwt jwt = jwtDecoder.decode(token);
                    String tenantId = jwt.getClaimAsString("tenant_id");
                    if (tenantId != null && !tenantId.isBlank()) {
                        UUID tid = UUID.fromString(tenantId);
                        TenantContext.setTenantId(tid);
                    }
                } catch (JwtException | IllegalArgumentException e) {
                    // token invalido: segue adiante, o security chain rejeitara
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
