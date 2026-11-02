package br.com.tjosefftech.pdv.sync;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class SyncDtos {
    private SyncDtos() {}

    public record ItemDTO(UUID produtoId, int quantidade, long precoCentavos) {}
    public record PagamentoDTO(String forma, long valorCentavos) {}
    public record VendaDTO(UUID id, UUID lojaId, UUID caixaId, Instant criadaEm,
                           long totalCentavos, List<ItemDTO> itens, List<PagamentoDTO> pagamentos) {}
    public record SyncVendasRequest(List<VendaDTO> vendas) {}

    /** status: ACEITA | DUPLICADA | REJEITADA */
    public record ResultadoVenda(UUID id, String status, String motivo) {}
    public record SyncVendasResponse(List<ResultadoVenda> resultados) {}
}
