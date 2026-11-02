CREATE TABLE loja (
  id UUID PRIMARY KEY,
  nome VARCHAR(255) NOT NULL,
  fuso VARCHAR(3) NOT NULL,
  ativa BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE caixa (
  id UUID PRIMARY KEY,
  loja_id UUID NOT NULL REFERENCES loja(id),
  nome VARCHAR(255) NOT NULL
);

CREATE TABLE venda (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  loja_id UUID NOT NULL REFERENCES loja(id),
  caixa_id UUID NOT NULL REFERENCES caixa(id),
  criada_em TIMESTAMPTZ NOT NULL,
  recebida_em TIMESTAMPTZ NOT NULL DEFAULT now(),
  total_centavos BIGINT NOT NULL
);

CREATE TABLE venda_item (
  venda_id UUID NOT NULL REFERENCES venda(id),
  tenant_id UUID NOT NULL,
  produto_id UUID NOT NULL,
  quantidade INT NOT NULL,
  preco_centavos BIGINT NOT NULL
);

CREATE TABLE venda_pagamento (
  venda_id UUID NOT NULL REFERENCES venda(id),
  tenant_id UUID NOT NULL,
  forma TEXT NOT NULL,
  valor_centavos BIGINT NOT NULL
);

CREATE INDEX idx_venda_loja_criada_em ON venda (loja_id, criada_em);
CREATE INDEX idx_venda_criada_em ON venda (criada_em);

CREATE TABLE usuario_loja (
  usuario_id UUID NOT NULL,
  loja_id UUID NOT NULL,
  PRIMARY KEY (usuario_id, loja_id)
);

CREATE TABLE venda_resumo_dia (
  id UUID PRIMARY KEY,
  dia TIMESTAMPTZ NOT NULL,
  loja_id UUID NOT NULL,
  total_centavos BIGINT NOT NULL,
  total_reais DECIMAL(10,2) NOT NULL,
  criado_em TIMESTAMPTZ NOT NULL DEFAULT now()
);
