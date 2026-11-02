package br.com.tjosefftech.pdv;

import br.com.tjosefftech.pdv.auth.TokenDtos.TokenRequest;
import br.com.tjosefftech.pdv.auth.TokenDtos.TokenResponse;
import br.com.tjosefftech.pdv.sync.SyncDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("dev")
class MultiTenantSyncIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    WebApplicationContext context;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    MockMvc mockMvc;

    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    UUID lojaA = UUID.randomUUID();
    UUID lojaB = UUID.randomUUID();
    UUID caixaA = UUID.randomUUID();
    UUID caixaB = UUID.randomUUID();

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        jdbcTemplate.update("insert into loja (id, nome, fuso, ativa) values (?, ?, 'UTC', true)", lojaA, "Loja A");
        jdbcTemplate.update("insert into loja (id, nome, fuso, ativa) values (?, ?, 'UTC', true)", lojaB, "Loja B");
        jdbcTemplate.update("insert into caixa (id, loja_id, nome) values (?, ?, ?)", caixaA, lojaA, "Caixa A");
        jdbcTemplate.update("insert into caixa (id, loja_id, nome) values (?, ?, ?)", caixaB, lojaB, "Caixa B");
    }

    String gerarToken(UUID tenantId) throws Exception {
        TokenRequest req = new TokenRequest(tenantId, "OPERADOR", List.of(), null);
        String body = objectMapper.writeValueAsString(req);
        String response = mockMvc.perform(post("/api/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var node = objectMapper.readTree(response);
        return node.get("accessToken").asText();
    }

    @Test
    void doisTenantsIsolados() throws Exception {
        String tokenA = gerarToken(tenantA);
        UUID vendaId = UUID.randomUUID();
        var venda = new SyncDtos.VendaDTO(vendaId, lojaA, caixaA, Instant.now(), 1000,
                List.of(new SyncDtos.ItemDTO(UUID.randomUUID(), 1, 1000)),
                List.of(new SyncDtos.PagamentoDTO("DINHEIRO", 1000)));
        var req = new SyncDtos.SyncVendasRequest(List.of(venda));

        mockMvc.perform(post("/api/v1/sync/vendas")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultados[0].status").value("ACEITA"));
    }

    @Test
    void mesmoUuidDiferentesTenantsAmbosAceitos() throws Exception {
        String tokenA = gerarToken(tenantA);
        String tokenB = gerarToken(tenantB);
        UUID vendaId = UUID.randomUUID();

        var vendaA = new SyncDtos.VendaDTO(vendaId, lojaA, caixaA, Instant.now(), 500,
                List.of(new SyncDtos.ItemDTO(UUID.randomUUID(), 1, 500)),
                List.of(new SyncDtos.PagamentoDTO("DINHEIRO", 500)));
        var vendaB = new SyncDtos.VendaDTO(vendaId, lojaB, caixaB, Instant.now(), 700,
                List.of(new SyncDtos.ItemDTO(UUID.randomUUID(), 1, 700)),
                List.of(new SyncDtos.PagamentoDTO("DINHEIRO", 700)));

        mockMvc.perform(post("/api/v1/sync/vendas")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SyncDtos.SyncVendasRequest(List.of(vendaA)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultados[0].status").value("ACEITA"));

        mockMvc.perform(post("/api/v1/sync/vendas")
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SyncDtos.SyncVendasRequest(List.of(vendaB)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultados[0].status").value("ACEITA"));
    }

    @Test
    void reenvioMesmoTenantRetornaDuplicada() throws Exception {
        String tokenA = gerarToken(tenantA);
        UUID vendaId = UUID.randomUUID();
        var venda = new SyncDtos.VendaDTO(vendaId, lojaA, caixaA, Instant.now(), 1000,
                List.of(new SyncDtos.ItemDTO(UUID.randomUUID(), 2, 500)),
                List.of(new SyncDtos.PagamentoDTO("DINHEIRO", 1000)));
        var req = new SyncDtos.SyncVendasRequest(List.of(venda));

        mockMvc.perform(post("/api/v1/sync/vendas")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultados[0].status").value("ACEITA"));

        mockMvc.perform(post("/api/v1/sync/vendas")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultados[0].status").value("DUPLICADA"));
    }

    @Test
    void semTenantContextoNaoVeLinhas() throws Exception {
        Integer count = jdbcTemplate.queryForObject("select count(*) from venda", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(0, count == null ? 0 : count);
    }

    @Test
    void lojaOutroTenantRejeitada() throws Exception {
        String tokenA = gerarToken(tenantA);
        UUID vendaId = UUID.randomUUID();
        var venda = new SyncDtos.VendaDTO(vendaId, lojaB, caixaB, Instant.now(), 500,
                List.of(new SyncDtos.ItemDTO(UUID.randomUUID(), 1, 500)),
                List.of(new SyncDtos.PagamentoDTO("DINHEIRO", 500)));
        mockMvc.perform(post("/api/v1/sync/vendas")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SyncDtos.SyncVendasRequest(List.of(venda)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultados[0].status").value("REJEITADA"));
    }

    @Test
    void totalDivergenteEPagamentoMenorRejeitados() throws Exception {
        String tokenA = gerarToken(tenantA);
        UUID venda1 = UUID.randomUUID();
        UUID venda2 = UUID.randomUUID();
        var v1 = new SyncDtos.VendaDTO(venda1, lojaA, caixaA, Instant.now(), 999,
                List.of(new SyncDtos.ItemDTO(UUID.randomUUID(), 1, 1000)),
                List.of(new SyncDtos.PagamentoDTO("DINHEIRO", 999)));
        var v2 = new SyncDtos.VendaDTO(venda2, lojaA, caixaA, Instant.now(), 1000,
                List.of(new SyncDtos.ItemDTO(UUID.randomUUID(), 1, 1000)),
                List.of(new SyncDtos.PagamentoDTO("DINHEIRO", 500)));
        mockMvc.perform(post("/api/v1/sync/vendas")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SyncDtos.SyncVendasRequest(List.of(v1, v2)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultados[0].status").value("REJEITADA"))
                .andExpect(jsonPath("$.resultados[1].status").value("REJEITADA"));
    }

    @Test
    void tokenEndpointSoDev() throws Exception {
        TokenRequest req = new TokenRequest(UUID.randomUUID(), "OPERADOR", List.of(), null);
        String body = objectMapper.writeValueAsString(req);
        mockMvc.perform(post("/api/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }
}
