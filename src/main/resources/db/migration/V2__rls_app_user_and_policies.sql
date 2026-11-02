-- V2: Criar role de aplicação sem privilégios de bypass e ajustar RLS
DO
$$
BEGIN
   IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'pdv_app') THEN
      CREATE ROLE pdv_app WITH LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD '${PDV_APP_PASSWORD:-pdv_app}';
   END IF;
END
$$;

-- Garantir que o dono (pdv) tenha privilégios necessários para migrações
GRANT USAGE ON SCHEMA public TO pdv_app;
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA public TO pdv_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO pdv_app;

ALTER DEFAULT PRIVILEGES FOR ROLE pdv GRANT SELECT, INSERT, UPDATE ON TABLES TO pdv_app;
ALTER DEFAULT PRIVILEGES FOR ROLE pdv GRANT USAGE, SELECT ON SEQUENCES TO pdv_app;

-- Forçar RLS nas tabelas existentes
ALTER TABLE venda FORCE ROW LEVEL SECURITY;

-- Recriar política com NULLIF e WITH CHECK
DROP POLICY IF EXISTS tenant_isolation ON venda;
CREATE POLICY tenant_isolation ON venda
  USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
