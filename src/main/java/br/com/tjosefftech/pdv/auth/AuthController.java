package br.com.tjosefftech.pdv.auth;

import br.com.tjosefftech.pdv.auth.TokenDtos.TokenRequest;
import br.com.tjosefftech.pdv.auth.TokenDtos.TokenResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// SOMENTE PARA DESENVOLVIMENTO
// Em produção, o token deve sair de login real (usuário e senha),
// com o tenant vindo do cadastro do usuário no banco, nunca de um campo enviado pelo cliente.
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
        String token = tokenService.gerarToken(req.tenantId(), req.lojaId());
        return new TokenResponse(token, "Bearer", tokenService.getExpiresIn());
    }
}
