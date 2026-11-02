package br.com.tjosefftech.pdv;

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

import java.sql.Timestamp;
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

        // Criar lojas/caixas por tenant (usando conexão do contexto - Flyway já rodou com dono pdv)
        jdbcTemplate.update("insert into loja (id, tenant_id, nome) values (?, ?, ?)", lojaA, tenantA, "Loja A");
        jdbcTemplate.update("insert into loja (id, tenant_id, nome) values (?, ?, ?)", lojaB, tenantB, "Loja B");
        jdbcTemplate.update("insert into caixa (id, tenant_id, loja_id, nome) values (?, ?, ?, ?)", caixaA, tenantA, lojaA, "Caixa A");
        jdbcTemplate.update("insert into caixa (id, tenant_id, loja_id, nome) values (?, ?, ?, ?)", caixaB, tenantB, lojaB, "Caixa B");
    }

    String gerarToken(UUID tenantId) throws Exception {
        String body = objectMapper.writeValueAsString(new TokenReq(tenantId, null));
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

        // B não vê com seu próprio contexto
        jdbcTemplate.update("select set_config('app.tenant_id', ?, true)", tenantB.toString());
        Integer count = jdbcTemplate.queryForObject("select count(*) from venda where id = ?", Integer.class, vendaId);
        // Com RLS e role pdv_app no contexto da app, mas aqui usamos JdbcTemplate da aplicação; 
        // o isolamento é validado também pelo fato de B não conseguir inserir/ler via seu token
        // Teste direto: setar tenant B e contar
        // Reset
        jdbcTemplate.update("select set_config('app.tenant_id', ?, true)", tenantB.toString());
        Integer countB = jdbcTemplate.queryForObject("select count(*) from venda where id = ?", Integer.class, vendaId);
        org.junit.jupiter.api.Assertions.assertEquals(0, countB == null ? 0 : countB);
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
        jdbcTemplate.update("select set_config('app.tenant_id', '', true)");
        Integer count = jdbcTemplate.queryForObject("select count(*) from venda", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(0, count == null ? 0 : count);
    }

    @Test
    void lojaOutroTenantRejeitada() throws Exception {
        String tokenA = gerarToken(tenantA);
        UUID vendaId = UUID.randomUUID();
        // lojaB pertence a tenantB
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
        // Em perfil dev deve funcionar
        String body = objectMapper.writeValueAsString(new TokenReq(UUID.randomUUID(), null));
        mockMvc.perform(post("/api/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    record TokenReq(UUID tenantId, UUID lojaId) {}
}
