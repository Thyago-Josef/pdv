-- V3: Multi-tenant schema - loja/caixa, tenant_id em filhos, chave composta, FKs compostas, RLS
-- Criar tabelas loja e caixa
CREATE TABLE loja (
  id        UUID NOT NULL,
  tenant_id UUID NOT NULL,
  nome      TEXT NOT NULL,
  PRIMARY KEY (tenant_id, id)
);

CREATE TABLE caixa (
  id        UUID NOT NULL,
  tenant_id UUID NOT NULL,
  loja_id   UUID NOT NULL,
  nome      TEXT NOT NULL,
  PRIMARY KEY (tenant_id, id),
  CONSTRAINT fk_caixa_loja FOREIGN KEY (tenant_id, loja_id) REFERENCES loja (tenant_id, id)
);

ALTER TABLE loja ENABLE ROW LEVEL SECURITY;
ALTER TABLE loja FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_loja ON loja;
CREATE POLICY tenant_isolation_loja ON loja
  USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE caixa ENABLE ROW LEVEL SECURITY;
ALTER TABLE caixa FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_caixa ON caixa;
CREATE POLICY tenant_isolation_caixa ON caixa
  USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON loja TO pdv_app;
GRANT SELECT, INSERT, UPDATE ON caixa TO pdv_app;

-- Adicionar tenant_id em venda_item e venda_pagamento
ALTER TABLE venda_item ADD COLUMN IF NOT EXISTS tenant_id UUID;
ALTER TABLE venda_pagamento ADD COLUMN IF NOT EXISTS tenant_id UUID;

-- Preencher a partir de venda
UPDATE venda_item vi
SET tenant_id = v.tenant_id
FROM venda v
WHERE vi.venda_id = v.id AND vi.tenant_id IS NULL;

UPDATE venda_pagamento vp
SET tenant_id = v.tenant_id
FROM venda v
WHERE vp.venda_id = v.id AND vp.tenant_id IS NULL;

-- Tornar NOT NULL
ALTER TABLE venda_item ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE venda_pagamento ALTER COLUMN tenant_id SET NOT NULL;

-- Trocar PK de venda para (tenant_id, id)
ALTER TABLE venda DROP CONSTRAINT IF EXISTS venda_pkey;
ALTER TABLE venda ADD PRIMARY KEY (tenant_id, id);

-- Ajustar FKs das filhas para chave composta
ALTER TABLE venda_item DROP CONSTRAINT IF EXISTS venda_item_venda_id_fkey;
ALTER TABLE venda_item ADD CONSTRAINT venda_item_venda_id_tenant_fkey
  FOREIGN KEY (tenant_id, venda_id) REFERENCES venda (tenant_id, id);

ALTER TABLE venda_pagamento DROP CONSTRAINT IF EXISTS venda_pagamento_venda_id_fkey;
ALTER TABLE venda_pagamento ADD CONSTRAINT venda_pagamento_venda_id_tenant_fkey
  FOREIGN KEY (tenant_id, venda_id) REFERENCES venda (tenant_id, id);

-- Habilitar e forçar RLS nas filhas
ALTER TABLE venda_item ENABLE ROW LEVEL SECURITY;
ALTER TABLE venda_item FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_venda_item ON venda_item;
CREATE POLICY tenant_isolation_venda_item ON venda_item
  USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE venda_pagamento ENABLE ROW LEVEL SECURITY;
ALTER TABLE venda_pagamento FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_venda_pagamento ON venda_pagamento;
CREATE POLICY tenant_isolation_venda_pagamento ON venda_pagamento
  USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON venda_item TO pdv_app;
GRANT SELECT, INSERT, UPDATE ON venda_pagamento TO pdv_app;
