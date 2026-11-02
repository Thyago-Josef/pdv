package br.com.tjosefftech.pdv.auth;

import br.com.tjosefftech.pdv.auth.TokenDtos.TokenRequest;
import br.com.tjosefftech.pdv.auth.TokenDtos.TokenResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

// SOMENTE PARA DESENVOLVIMENTO
// Em producao, o token deve sair de login real (usuario e senha),
// com o tenant vindo do cadastro do usuario no banco, nunca de um campo enviado pelo cliente.
@RestController
@RequestMapping("/api/auth")
@Profile("dev")
public class AuthController {

    private final TokenService tokenService;

    public AuthController(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @PostMapping("/token")
    public TokenResponse gerar(@RequestBody TokenRequest req) {
        String token = tokenService.gerarToken(req.tenantId(), req.perfil(), req.lojas(), req.lojaId());
        return new TokenResponse(token, "Bearer", tokenService.getExpiresIn());
    }
}
