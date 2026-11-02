package br.com.tjosefftech.pdv.sync;

import br.com.tjosefftech.pdv.sync.SyncDtos.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/sync")
public class SyncController {

    private final SyncVendaService service;

    public SyncController(SyncVendaService service)
    {
        this.service = service;
    }

    @PostMapping("/vendas")
    public SyncVendasResponse receber(@AuthenticationPrincipal Jwt jwt,
                                      @RequestBody SyncVendasRequest req) {
        // tenant vem SEMPRE do token, nunca do corpo da requisição
        UUID tenantId = UUID.fromString(jwt.getClaimAsString("tenant_id"));
        UUID lojaIdDoToken = null;
        String lojaClaim = jwt.getClaimAsString("loja_id");
        if (lojaClaim != null && !lojaClaim.isBlank()) {
            lojaIdDoToken = UUID.fromString(lojaClaim);
        }
        return service.processar(tenantId, lojaIdDoToken, req.vendas());
    }
}
