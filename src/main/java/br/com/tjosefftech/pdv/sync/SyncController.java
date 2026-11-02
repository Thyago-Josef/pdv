package br.com.tjosefftech.pdv.sync;

import br.com.tjosefftech.pdv.sync.SyncDtos.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/sync")
public class SyncController {

    private final SyncVendaService service;

    public SyncController(SyncVendaService service) {
        this.service = service;
    }

    @PostMapping("/vendas")
    public SyncVendasResponse receber(@AuthenticationPrincipal Jwt jwt,
                                      @RequestBody SyncVendasRequest req) {
        UUID tenantId = UUID.fromString(jwt.getClaimAsString("tenant_id"));
        String perfil = jwt.getClaimAsString("perfil");
        List<String> lojasClaim = jwt.getClaimAsString("lojas") != null
                ? java.util.Arrays.asList(jwt.getClaimAsString("lojas").split(","))
                : List.of();

        UUID lojaIdDoToken = null;
        String lojaClaim = jwt.getClaimAsString("loja_id");
        if (lojaClaim != null && !lojaClaim.isBlank()) {
            lojaIdDoToken = UUID.fromString(lojaClaim);
        }

        return service.processar(tenantId, lojaIdDoToken, perfil, lojasClaim, req.vendas());
    }
}
