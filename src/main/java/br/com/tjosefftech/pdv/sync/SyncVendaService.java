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
    public SyncVendasResponse processar(UUID tenantId, UUID lojaIdDoToken, String perfil, List<String> lojasClaim, List<VendaDTO> vendas) {
        List<ResultadoVenda> resultados = new ArrayList<>();
        for (VendaDTO v : vendas) {
            resultados.add(processarUma(tenantId, lojaIdDoToken, perfil, lojasClaim, v));
        }
        return new SyncVendasResponse(resultados);
    }

    public SyncVendasResponse processar(UUID tenantId, UUID lojaIdDoToken, List<VendaDTO> vendas) {
        return processar(tenantId, lojaIdDoToken, "OPERADOR", List.of(), vendas);
    }

    private ResultadoVenda processarUma(UUID tenantId, UUID lojaIdDoToken, String perfil, List<String> lojasClaim, VendaDTO v) {
        if (v.itens() == null || v.itens().isEmpty()) {
            return new ResultadoVenda(v.id(), "REJEITADA", "venda sem itens");
        }
        for (ItemDTO i : v.itens()) {
            if (i.quantidade() <= 0) {
                return new ResultadoVenda(v.id(), "REJEITADA", "quantidade invalida");
            }
            if (i.precoCentavos() < 0) {
                return new ResultadoVenda(v.id(), "REJEITADA", "preco invalido");
            }
        }
        if (v.pagamentos() == null || v.pagamentos().isEmpty()) {
            return new ResultadoVenda(v.id(), "REJEITADA", "sem pagamentos");
        }
        for (PagamentoDTO p : v.pagamentos()) {
            if (p.valorCentavos() < 0) {
                return new ResultadoVenda(v.id(), "REJEITADA", "pagamento invalido");
            }
        }

        long totalCalculado = v.itens().stream().mapToLong(i -> i.quantidade() * i.precoCentavos()).sum();
        if (totalCalculado != v.totalCentavos()) {
            return new ResultadoVenda(v.id(), "REJEITADA", "total divergente: esperado " + totalCalculado);
        }
        long pago = v.pagamentos().stream().mapToLong(PagamentoDTO::valorCentavos).sum();
        if (pago < v.totalCentavos()) {
            return new ResultadoVenda(v.id(), "REJEITADA", "pagamento menor que o total");
        }

        Integer lojaCount = jdbc.queryForObject(
                "select count(*) from loja where id = ?",
                Integer.class, v.lojaId());
        if (lojaCount == null || lojaCount == 0) {
            return new ResultadoVenda(v.id(), "REJEITADA", "loja nao pertence ao tenant");
        }

        Integer caixaCount = jdbc.queryForObject(
                "select count(*) from caixa where id = ? and loja_id = ?",
                Integer.class, v.caixaId(), v.lojaId());
        if (caixaCount == null || caixaCount == 0) {
            return new ResultadoVenda(v.id(), "REJEITADA", "caixa nao pertence a loja/tenant");
        }

        if (lojaIdDoToken != null && !lojaIdDoToken.equals(v.lojaId())) {
            return new ResultadoVenda(v.id(), "REJEITADA", "loja divergente do token");
        }

        if (lojasClaim != null && !lojasClaim.isEmpty()) {
            boolean lojaNoEscopo = lojasClaim.stream().anyMatch(l -> l != null && l.equals(v.lojaId().toString()));
            if (!lojaNoEscopo) {
                return new ResultadoVenda(v.id(), "REJEITADA", "loja fora do escopo do usuario");
            }
        }

        int inseridas = jdbc.update("""
                insert into venda (id, tenant_id, loja_id, caixa_id, criada_em, total_centavos)
                values (?, ?, ?, ?, ?, ?)
                on conflict (id) do nothing
                """, v.id(), tenantId, v.lojaId(), v.caixaId(),
                java.sql.Timestamp.from(v.criadaEm()), v.totalCentavos());

        if (inseridas == 0) {
            return new ResultadoVenda(v.id(), "DUPLICADA", null);
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
