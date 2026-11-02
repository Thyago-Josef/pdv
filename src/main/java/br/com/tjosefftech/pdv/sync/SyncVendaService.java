package br.com.tjosefftech.pdv.sync;

import br.com.tjosefftech.pdv.sync.SyncDtos.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class SyncVendaService {

    private final JdbcTemplate jdbc;

    public SyncVendaService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public SyncVendasResponse processar(UUID tenantId, UUID lojaIdDoToken, List<VendaDTO> vendas) {
        // ativa o Row Level Security para esta transação
        jdbc.queryForObject("select set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());

        List<ResultadoVenda> resultados = new ArrayList<>();
        for (VendaDTO v : vendas) {
            resultados.add(processarUma(tenantId, lojaIdDoToken, v));
        }
        return new SyncVendasResponse(resultados);
    }

    public SyncVendasResponse processar(UUID tenantId, List<VendaDTO> vendas) {
        return processar(tenantId, null, vendas);
    }

    private ResultadoVenda processarUma(UUID tenantId, UUID lojaIdDoToken, VendaDTO v) {
        // Validações básicas
        if (v.itens() == null || v.itens().isEmpty()) {
            return new ResultadoVenda(v.id(), "REJEITADA", "venda sem itens");
        }
        for (ItemDTO i : v.itens()) {
            if (i.quantidade() <= 0) {
                return new ResultadoVenda(v.id(), "REJEITADA", "quantidade inválida");
            }
            if (i.precoCentavos() < 0) {
                return new ResultadoVenda(v.id(), "REJEITADA", "preco inválido");
            }
        }
        if (v.pagamentos() == null || v.pagamentos().isEmpty()) {
            return new ResultadoVenda(v.id(), "REJEITADA", "sem pagamentos");
        }
        for (PagamentoDTO p : v.pagamentos()) {
            if (p.valorCentavos() < 0) {
                return new ResultadoVenda(v.id(), "REJEITADA", "pagamento inválido");
            }
        }

        // 1) Nunca confie no cliente: recalcula o total
        long totalCalculado = v.itens().stream()
                .mapToLong(i -> i.quantidade() * i.precoCentavos()).sum();
        if (totalCalculado != v.totalCentavos()) {
            return new ResultadoVenda(v.id(), "REJEITADA", "total divergente: esperado " + totalCalculado);
        }
        long pago = v.pagamentos().stream().mapToLong(PagamentoDTO::valorCentavos).sum();
        if (pago < v.totalCentavos()) {
            return new ResultadoVenda(v.id(), "REJEITADA", "pagamento menor que o total");
        }

        // Valida loja/caixa pertencentes ao tenant
        Integer lojaCount = jdbc.queryForObject(
                "select count(*) from loja where tenant_id = ? and id = ?",
                Integer.class, tenantId, v.lojaId());
        if (lojaCount == null || lojaCount == 0) {
            return new ResultadoVenda(v.id(), "REJEITADA", "loja não pertence ao tenant");
        }

        Integer caixaCount = jdbc.queryForObject(
                "select count(*) from caixa where tenant_id = ? and id = ? and loja_id = ?",
                Integer.class, tenantId, v.caixaId(), v.lojaId());
        if (caixaCount == null || caixaCount == 0) {
            return new ResultadoVenda(v.id(), "REJEITADA", "caixa não pertence à loja/tenant");
        }

        // Se JWT tiver loja_id, rejeita se diferente
        if (lojaIdDoToken != null && !lojaIdDoToken.equals(v.lojaId())) {
            return new ResultadoVenda(v.id(), "REJEITADA", "loja divergente do token");
        }

        // TODO: conferir preço vs. tabela, limite de desconto, permissão do operador

        // 2) Idempotência: PK composta (tenant_id, id)
        int inseridas = jdbc.update("""
                insert into venda (id, tenant_id, loja_id, caixa_id, criada_em, total_centavos)
                values (?, ?, ?, ?, ?, ?)
                on conflict (tenant_id, id) do nothing
                """, v.id(), tenantId, v.lojaId(), v.caixaId(),
                java.sql.Timestamp.from(v.criadaEm()), v.totalCentavos());

        if (inseridas == 0) {
            return new ResultadoVenda(v.id(), "DUPLICADA", null); // já recebida: cliente pode marcar como enviada
        }

        v.itens().forEach(i -> jdbc.update(
                "insert into venda_item (venda_id, tenant_id, produto_id, quantidade, preco_centavos) values (?, ?, ?, ?, ?)",
                v.id(), tenantId, i.produtoId(), i.quantidade(), i.precoCentavos()));
        v.pagamentos().forEach(p -> jdbc.update(
                "insert into venda_pagamento (venda_id, tenant_id, forma, valor_centavos) values (?, ?, ?, ?)",
                v.id(), tenantId, p.forma(), p.valorCentavos()));

        return new ResultadoVenda(v.id(), "ACEITA", null);
    }
}
