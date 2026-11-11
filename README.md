# PDV - Sistema de Sincronizacao Multi-tenant

## Visao Geral

Backend Spring Boot de PDV SaaS multi-tenant com **um banco PostgreSQL por tenant**.

Arquitetura:
1. **Banco de Controle** (unico, nosso): guarda tenants e o mapeamento tenant -> banco.
2. **Banco por Tenant**: contem lojas, caixas, vendas, etc. Varias lojas por banco.

O `tenant_id` vem exclusivamente do claim `tenant_id` do JWT. O Spring resolve o banco do tenant
a partir do banco de controle. Nunca aceitar `tenant_id`, loja de outro tenant ou nome de banco
vindos do corpo, query ou header da requisicao.

## Tecnologias

- Java 17
- Spring Boot 4.1.2
- Spring Security (OAuth2 Resource Server - JWT HS256)
- Spring JDBC (JdbcTemplate)
- PostgreSQL 16
- Flyway
- Testcontainers (testes de integracao)
- springdoc-openapi (Swagger UI)

## Bancos de Dados

| Banco | Container (interno) | Host (maquina) | Porta | Database | Usuario | Senha | Uso |
|---|---|---|---|---|---|---|---|
| **Controle** | `pdv-postgres:5432` | `localhost` | `5440` | `pdv_control` | `postgres` | `postgres` | Tabela `tenant`, mapeamento tenant -> banco |
| **Tenant (dev)** | `pdv-postgres:5432` | `localhost` | `5440` | `pdv` | `postgres` | `postgres` | Lojas, caixas, vendas do tenant em desenvolvimento |

- **`pdv_control`** (banco de controle): criado pelo script `docker/init/01-create-databases.sql` na primeira inicializacao do container. Guarda a tabela `tenant` com status e credenciais de conexao de cada banco de tenant.
- **`pdv`** (banco de tenant dev): criado automaticamente pelo Postgres via `POSTGRES_DB=pdv`. Usado como banco de tenant padrao para desenvolvimento/testes locais.
- **Bancos de tenant reais**: em producao, cada tenant tera seu proprio banco (ex.: `tenant_<uuid>`), provisionado pelo servico de provisionamento e registrado em `pdv_control.tenant`.

## Docker

O projeto inclui `docker-compose.yml` para subir o PostgreSQL local com os bancos necessarios.

### Subir o banco
```bash
docker compose up -d
```

Isso cria:
- container `pdv-postgres` (Postgres 16)
- volume `pdv_postgres_data` para persistencia
- database `pdv` (tenant dev)
- database `pdv_control` (controle) via `docker/init/01-create-databases.sql`

### Comandos uteis
```bash
# Ver status do container
docker compose ps

# Ver logs
docker compose logs -f postgres

# Parar e remover container (mantem volume)
docker compose down

# Parar e remover container + volume (apaga dados)
docker compose down -v

# Listar bancos no container
docker exec -it pdv-postgres psql -U postgres -c "\l"

# Acessar psql no banco de controle
docker exec -it pdv-postgres psql -U postgres -d pdv_control

# Acessar psql no banco de tenant (dev)
docker exec -it pdv-postgres psql -U postgres -d pdv
```

## Acesso via pgAdmin (local)

Voce pode usar o **pgAdmin instalado localmente** (nao precisa rodar pgAdmin via Docker).

### Conexao - Banco de Controle
- **Name**: `PDV - Controle`
- **Host name/address**: `localhost` (ou `127.0.0.1`)
- **Port**: `5440`
- **Maintenance database**: `pdv_control`
- **Username**: `postgres`
- **Password**: `postgres`

### Conexao - Banco de Tenant (dev)
- **Name**: `PDV - Tenant Dev`
- **Host name/address**: `localhost` (ou `127.0.0.1`)
- **Port**: `5440`
- **Maintenance database**: `pdv`
- **Username**: `postgres`
- **Password**: `postgres`

> Se o pgAdmin estiver rodando **dentro de outro container Docker**, use `host.docker.internal` no host (em vez de `localhost`) e garanta que a porta `5440` esteja publicada.

## Arquitetura

### 1) Banco de Controle (`db/control`)

Migration: `V1__tenant.sql`

Tabela `tenant`:
- `id` UUID PK
- `nome` VARCHAR(255)
- `status` VARCHAR(20) CHECK (ATIVO|SUSPENSO)
- `db_host`, `db_port`, `db_name`, `db_user`, `db_password_enc` BYTEA
- `criado_em` TIMESTAMPTZ

`db_password_enc`: senha do usuario de aplicacao daquele banco, criptografada com AES-GCM;
chave mestra via variavel de ambiente `MASTER_KEY` (falhar na inicializacao se ausente ou < 32 bytes).

### 2) Banco por Tenant (`db/tenant`)

Migration: `V1__baseline.sql`

Tabelas:
- `loja` (id, nome, fuso, ativa)
- `caixa` (id, loja_id FK loja, nome)
- `venda` (id PK, tenant_id, loja_id FK loja, caixa_id FK caixa, criada_em, recebida_em, total_centavos)
- `venda_item` (venda_id FK, tenant_id, produto_id, quantidade, preco_centavos)
- `venda_pagamento` (venda_id FK, tenant_id, forma, valor_centavos)
- `usuario_loja` (usuario_id, loja_id)
- `venda_resumo_dia` (opcional/TODO)

Sem RLS neste banco.

### 3) Roteamento por Tenant

- `TenantContext` (ThreadLocal) com set/clear/get.
- `TenantRoutingDataSource` (implementa DataSource) com pool HikariCP por tenant (max 5 conexoes).
- `TenantContextFilter` (OncePerRequestFilter) preenche o TenantContext a partir do claim
  `tenant_id` do JWT e limpa no `finally`.
- Tenant inexistente ou SUSPENSO -> 403 e nenhuma conexao aberta.
- JdbcTemplate dos services passa a usar o DataSource roteado.

### 4) Autenticacao e Emissao de Token

- `POST /api/auth/token` (somente `@Profile("dev")`).
- Recebe `tenantId`, `perfil` (ADMIN|GERENTE|OPERADOR) e `lojas` (lista de UUIDs), emite JWT com
  claims `tenant_id`, `perfil`, `lojas`, e opcionalmente `loja_id`/`caixa_id` para OPERADOR.
- Em producao o token vem de login real (usuario e senha); tenant e escopo vêm do cadastro do usuario.

### 5) Sincronizacao de Vendas

`POST /api/v1/sync/vendas` - contrato de DTO inalterado.

Status: `ACEITA | DUPLICADA | REJEITADA`.

Regras de validacao:
- ao menos 1 item; quantidade > 0; valores >= 0; total recalculado igual ao informado; pagamento >= total.
- loja deve existir no banco do tenant; caixa deve pertencer a loja.
- OPERADOR enviando loja/caixa diferente do token -> REJEITADA.
- GERENTE enviando loja fora da lista `lojas` do token -> REJEITADA.

Idempotencia por PK (id) dentro do banco do tenant: reenvio devolve `DUPLICADA`.

### 6) Relatorios

- `GET /api/v1/relatorios/vendas?lojaId=<uuid>&de=<iso>&ate=<iso>`
- `GET /api/v1/relatorios/vendas?lojas=todas&de=<iso>&ate=<iso>`

Escopo aplicado no servidor: "todas" = lojas permitidas ao usuario (ADMIN = todas do tenant;
GERENTE = as do claim `lojas`; OPERADOR = apenas a propria). lojaId fora do escopo -> 403.

### 7) Remocao de RLS

RLS foi removido. Isolamento via banco por tenant + validacoes no sync.

## Configuracao

### Variaveis de ambiente

| Variavel | Descricao | Padrao (dev) |
|---|---|---|
| `JWT_SECRET` | Chave para assinar/verificar JWT HS256 (min 32 bytes) | dev-secret-key-that-is-at-least-32-bytes-long-for-testing |
| `MASTER_KEY` | Chave mestra AES-GCM (control) | - |
| `CONTROL_DB_URL` | JDBC do banco de controle | `jdbc:postgresql://localhost:5440/pdv_control` |
| `CONTROL_DB_USER` | Usuario do banco de controle | `postgres` |
| `CONTROL_DB_PASSWORD` | Senha do banco de controle | `postgres` |
| `PDV_APP_PASSWORD` | Senha do role `pdv_app` do tenant | `pdv_app` |
| `PDV_FLYWAY_USER` | Usuario Flyway | `pdv` |
| `PDV_FLYWAY_PASSWORD` | Senha Flyway | `pdv` |

### application.properties (padrao / producao)
- `app.control.datasource.*`: conecta no banco de controle (`pdv_control`).
- `spring.datasource.*`: datasource padrao/tenant (roteado).

### application-dev.properties (desenvolvimento)
- Perfil `dev`: ativa endpoint `/api/auth/token` e usa segredo JWT de desenvolvimento (sobrescrevivel por `JWT_SECRET`).

## Swagger / OpenAPI

- **Swagger UI**: `http://localhost:8080/swagger-ui.html`
- **OpenAPI JSON**: `http://localhost:8080/v3/api-docs`

## Migrations

- `db/control/V1__tenant.sql` - tabela `tenant` no banco de controle.
- `db/tenant/V1__baseline.sql` - baseline do banco do tenant (loja, caixa, venda, etc).
- `db/legacy/` - migracoes antigas (RLS) mantidas sem alterar.

## Testes

Testes de integracao com Testcontainers PostgreSQL 16.

## TODO / Proximos Passos

- Login real com credenciais e tenant do usuario no banco.
- Refresh token.
- Tabela de resumo diario `venda_resumo_dia`.
- Rotacao de MASTER_KEY.
- PgBouncer.
- Autorizacao por perfil detalhada.
