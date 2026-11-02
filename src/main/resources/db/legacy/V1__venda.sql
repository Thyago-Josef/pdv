CREATE TABLE venda (
  id              UUID PRIMARY KEY,              -- gerado no cliente: garante idempotência
  tenant_id       UUID NOT NULL,
  loja_id         UUID NOT NULL,
  caixa_id        UUID NOT NULL,
  criada_em       TIMESTAMPTZ NOT NULL,
  recebida_em     TIMESTAMPTZ NOT NULL DEFAULT now(),
  total_centavos  BIGINT NOT NULL
);
CREATE TABLE venda_item (
  venda_id        UUID NOT NULL REFERENCES venda(id),
  produto_id      UUID NOT NULL,
  quantidade      INT NOT NULL,
  preco_centavos  BIGINT NOT NULL
);
CREATE TABLE venda_pagamento (
  venda_id        UUID NOT NULL REFERENCES venda(id),
  forma           TEXT NOT NULL,
  valor_centavos  BIGINT NOT NULL
);

-- Isolamento entre lojas (Row Level Security)
ALTER TABLE venda ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON venda
  USING (tenant_id = current_setting('app.tenant_id')::uuid);
