# PDV - Sistema de Sincronização Multi-tenant

## Visão Geral

Sistema PDV SaaS multi-tenant com isolamento via **Row Level Security (RLS)** do PostgreSQL e autenticação **JWT (OAuth2 Resource Server)**.  
O `tenant_id` é extraído **exclusivamente do claim `tenant_id`** do JWT, nunca do corpo da requisição.  
Toda a aplicação utiliza **JdbcTemplate** (sem JPA). Banco: **PostgreSQL 16**.

## Tecnologias

- Java 17
- Spring Boot 4.1.2
- Spring Security (OAuth2 Resource Server - JWT HS256)
- Spring JDBC (JdbcTemplate)
- PostgreSQL 16
- Flyway
- Testcontainers (testes de integração)

## Arquitetura de Isolamento

### 1) Papéis de banco
- **`pdv`** - Dono das tabelas. Utilizado pelo **Flyway** para executar migrações.
- **`pdv_app`** - Role da aplicação. `LOGIN`, `NOSUPERUSER`, `NOBYPASSRLS`. Sem privilégios para ignorar RLS. Usado pelo `spring.datasource` da aplicação.

### 2) Row Level Security (RLS)
Todas as tabelas multi-tenant possuem **`ENABLE ROW LEVEL SECURITY`** e **`FORCE ROW LEVEL SECURITY`**:
- `venda`
- `venda_item`
- `venda_pagamento`
- `loja`
- `caixa`

As políticas utilizam:
```sql
tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
```
com **`USING`** e **`WITH CHECK`**.  
Caso `app.tenant_id` não esteja definido, `NULLIF` retorna `NULL` e a política filtra **zero linhas** (sem erro de cast).

Dentro de cada transação da sincronização, o serviço executa:
```sql
SELECT set_config('app.tenant_id', ?, true)
```

### 3) Chave composta e integridade
- `venda` possui **chave primária composta** `(tenant_id, id)`. O `id` é gerado no cliente (garante idempotência).
- `venda_item` e `venda_pagamento` possuem `tenant_id` NOT NULL, preenchido na migration a partir de `venda`, com **FKs compostas** `(tenant_id, venda_id)` referenciando `(tenant_id, id)` de `venda`.
- `loja` e `caixa` possuem PK composta `(tenant_id, id)` e FKs coerentes com o tenant.
- **Idempotência**: `INSERT ... ON CONFLICT (tenant_id, id) DO NOTHING`. Mesmo UUID enviado por **tenants diferentes** resulta em **vendas separadas e ambas ACEITAS**.

## Autenticação e Emissão de Token

### Perfis
- **Padrão (produção)**: `POST /api/auth/**` é negado. O endpoint de emissão **não existe**.
- **dev**: Ative com `spring.profiles.active=dev`. `POST /api/auth/token` liberado apenas neste perfil.

> **CRÍTICO - SOMENTE DESENVOLVIMENTO**  
> O endpoint `POST /api/auth/token` aceita `tenantId` (e opcionalmente `lojaId`) no corpo e emite um token JWT assinado. **Qualquer pessoa com acesso a ele pode gerar token para qualquer tenant**.  
> Em **produção**, a emissão deve vir de um **login real (usuário e senha)**. O `tenant_id` **deve vir do cadastro do usuário no banco de dados**, **nunca** enviado pelo cliente.  
> TODO: implementar login real com credenciais e obter tenant a partir do usuário autenticado. TODO: refresh token.

### Gerar token (apenas dev)
**Endpoint:** `POST /api/auth/token`

**Request Body:**
```json
{
  "tenantId": "e7a62f43-9b82-4c02-b7e2-2f3ab9e8c902",
  "lojaId": "c9a62b43-8f72-4e02-b7c1-2ab8e9f70213"
}
```
`lojaId` é opcional. Se informado, será gravado como claim `loja_id` no JWT.

**Response:**
```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "tokenType": "Bearer",
  "expiresIn": 3600
}
```

### Claims do JWT
- `iss` - issuer (`jwt.issuer`)
- `iat` - emissão
- `exp` - expiração (+1 hora)
- `sub` - `tenant_id`
- `tenant_id` - UUID do tenant (obrigatório)
- `loja_id` - UUID da loja (opcional, apenas em dev)

### Validação
- Assinado com **HS256** usando `jwt.secret`
- `jwt.secret` **obrigatório via variável de ambiente** e deve ter **>= 32 bytes**. A aplicação falha na inicialização caso inválido.
- Rotas protegidas exigem `Authorization: Bearer <accessToken>`
- Sessão **stateless**

## Sincronização de Vendas

**Endpoint:** `POST /api/v1/sync/vendas`

**Headers:**
```
Authorization: Bearer <accessToken>
Content-Type: application/json
```

**Request Body (contrato inalterado):**
```json
{
  "vendas": [
    {
      "id": "b7f3e8a2-9c42-4d02-b8e1-2ab7c9f80321",
      "lojaId": "c9a62b43-8f72-4e02-b7c1-2ab8e9f70213",
      "caixaId": "a7b82c43-9e62-4f02-b8a1-2c7ab9e80342",
      "criadaEm": "2026-11-01T10:30:00Z",
      "totalCentavos": 15000,
      "itens": [
        {
          "produtoId": "d8a72b43-9c62-4e02-b7f1-2ab9c8e70321",
          "quantidade": 2,
          "precoCentavos": 7500
        }
      ],
      "pagamentos": [
        {
          "forma": "DINHEIRO",
          "valorCentavos": 15000
        }
      ]
    }
  ]
}
```

**Response:**
```json
{
  "resultados": [
    {
      "id": "b7f3e8a2-9c42-4d02-b8e1-2ab7c9f80321",
      "status": "ACEITA",
      "motivo": null
    }
  ]
}
```

### Regras de validação
- `vendas` processadas em lote
- **Valores em centavos (long)**
- **Deve ter ao menos 1 item** - senão `REJEITADA` ("venda sem itens")
- **Quantidades > 0** - senão `REJEITADA` ("quantidade inválida")
- **Valores >= 0** - senão `REJEITADA` ("preco inválido" / "pagamento inválido")
- **Deve ter ao menos 1 pagamento** - senão `REJEITADA` ("sem pagamentos")
- **Total recalculado**: soma `(quantidade * precoCentavos)` deve igualar `totalCentavos` - senão `REJEITADA` ("total divergente: esperado ...")
- **Pagamento >= total** - senão `REJEITADA` ("pagamento menor que o total")
- **Loja deve pertencer ao tenant do token** - senão `REJEITADA` ("loja não pertence ao tenant")
- **Caixa deve pertencer à loja e ao tenant** - senão `REJEITADA` ("caixa não pertence à loja/tenant")
- **Se JWT possuir `loja_id`**, a `lojaId` da venda **deve ser igual** - senão `REJEITADA` ("loja divergente do token")

### Status
- `ACEITA` - Venda gravada com sucesso
- `DUPLICADA` - Já existe venda com `(tenant_id, id)`. **Não duplica** `venda`, `venda_item` ou `venda_pagamento`. Cliente pode marcar como enviada.
- `REJEITADA` - Falha de validação. `motivo` descreve o motivo.

## Regras de Isolamento: Tenant x Loja

O limite efetivo de isolamento é o **`tenant_id` do JWT** (aplicado via RLS).  
`loja_id` e `caixa_id` são apenas organizacionais dentro do tenant.

| Cenário | Configuração | Comportamento |
|---|---|---|
| **1 tenant por loja** | Cada loja com `tenant_id` único e token próprio | Isolamento total entre lojas. |
| **1 tenant por empresa/rede** | Todas as lojas compartilham o mesmo `tenant_id` | Isolamento por empresa. Diferenciar por `loja_id`/`caixa_id`. Relatórios por loja funcionam normalmente. |

Quem emite tokens define o modelo. Com PK composta `(tenant_id, id)`, **mesmo UUID de venda não causa colisão entre tenants**.

## Configuração

### Variáveis de ambiente
| Variável | Obrigatório | Padrão | Descrição |
|---|---|---|---|
| `JWT_SECRET` | Sim (produção) | vazio (dev pode usar valor padrão) | Chave para assinar/verificar JWT HS256. **Mínimo 32 bytes**. |
| `PDV_APP_PASSWORD` | Recomendado | `pdv_app` | Senha do role `pdv_app`. |
| `PDV_FLYWAY_PASSWORD` | Recomendado | `pdv` | Senha do usuário dono `pdv` (usado pelo Flyway). |

### application.properties (padrão / produção)
```properties
spring.application.name=pdv

# Datasource - aplicação usa role sem bypass RLS
spring.datasource.url=jdbc:postgresql://localhost:5440/pdv
spring.datasource.username=pdv_app
spring.datasource.password=${PDV_APP_PASSWORD:pdv_app}

# Flyway - migra com usuário dono das tabelas
spring.flyway.enabled=true
spring.flyway.user=pdv
spring.flyway.password=${PDV_FLYWAY_PASSWORD:pdv}

# JWT
jwt.secret=${JWT_SECRET:}
jwt.issuer=pdv
```

### application-dev.properties (desenvolvimento)
```properties
# Ativar com: --spring.profiles.active=dev
# POST /api/auth/token só existe com perfil dev
spring.datasource.url=jdbc:postgresql://localhost:5440/pdv
spring.datasource.username=pdv_app
spring.datasource.password=${PDV_APP_PASSWORD:pdv_app}

spring.flyway.enabled=true
spring.flyway.user=pdv
spring.flyway.password=${PDV_FLYWAY_PASSWORD:pdv}

jwt.secret=${JWT_SECRET:dev-secret-key-that-is-at-least-32-bytes-long-for-testing}
jwt.issuer=pdv
```

## Migrations
- `V1__venda.sql` - Tabelas `venda`, `venda_item`, `venda_pagamento` e RLS inicial (não alterada)
- `V2__rls_app_user_and_policies.sql` - Cria `pdv_app` (NOSUPERUSER/NOBYPASSRLS), grants, `FORCE ROW LEVEL SECURITY`, políticas com `NULLIF` + `WITH CHECK`
- `V3__multi_tenant_schema.sql` - Cria `loja`/`caixa`, adiciona `tenant_id` em filhas (preenchido), PK composta `(tenant_id,id)`, FKs compostas, RLS/FORCE em filhas

## Testes
Testes de integração com **Testcontainers PostgreSQL 16** em `MultiTenantSyncIntegrationTest`:
1. Dois tenants A e B: A grava venda; B não consegue enxergá-la (contexto `app.tenant_id` de B retorna zero linhas)
2. Mesmo UUID enviado por A e B: ambas `ACEITAS` e separadas (sem `DUPLICADA` indevida)
3. Reenvio mesmo UUID pelo mesmo tenant: `DUPLICADA`, sem duplicar `venda`, `venda_item`, `venda_pagamento`
4. Sem `app.tenant_id` definido, role `pdv_app` não vê nenhuma linha
5. Venda com `lojaId` de outro tenant é `REJEITADA`
6. Total divergente e pagamento menor que total são `REJEITADAS`
7. Sem perfil `dev`: `POST /api/auth/token` bloqueado/negado. Com perfil `dev`: funciona

## TODO / Próximos Passos
- **Login real**: endpoint de autenticação com usuário e senha
- **Tenant do usuário**: obter `tenant_id` do cadastro do usuário no banco (nunca do cliente)
- **Refresh token**: implementar rotação/refresh
- **Autorização por papel**: permissões por operador/caixa
- **Validação de preço/desconto**: conferir contra tabela de produtos/regras
