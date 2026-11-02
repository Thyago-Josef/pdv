package br.com.tjosefftech.pdv.tenant;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;
import java.util.logging.Logger;

public class TenantRoutingDataSource implements DataSource {

    public static final UUID DEFAULT_TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private final DataSource controlDataSource;
    private final ConcurrentHashMap<UUID, DataSource> tenantDataSources = new ConcurrentHashMap<>();

    public TenantRoutingDataSource(DataSource controlDataSource) {
        this.controlDataSource = controlDataSource;
    }

    private DataSource resolve() {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            tenantId = DEFAULT_TENANT_ID;
        }
        return tenantDataSources.computeIfAbsent(tenantId, id -> createTenantDataSource(id));
    }

    private DataSource createTenantDataSource(UUID tenantId) {
        try (var controlJdbc = controlDataSource.getConnection()) {
            var stmt = controlJdbc.createStatement();
            var rs = stmt.executeQuery("select db_name from tenant where id = " + tenantId + " and status = 'ATIVO'");
            if (!rs.next()) {
                throw new IllegalStateException("Tenant ativo nao encontrado: " + tenantId);
            }
            String dbName = rs.getString(1);
            var ds = new com.zaxxer.hikari.HikariDataSource();
            ds.setJdbcUrl("jdbc:postgresql://localhost:5432/" + dbName);
            ds.setUsername("pdv_app");
            ds.setPassword("pdv_app");
            ds.setMaximumPoolSize(5);
            ds.setPoolName("tenant-" + tenantId);
            return ds;
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao resolver datasource do tenant " + tenantId, e);
        }
    }

    @Override
    public Connection getConnection() throws SQLException {
        return resolve().getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return resolve().getConnection(username, password);
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        throw new UnsupportedOperationException("Wrapper nao suportado");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return false;
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {}

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {}

    @Override
    public int getLoginTimeout() throws SQLException {
        return 0;
    }

    @Override
    public Logger getParentLogger() {
        return Logger.getLogger("tenant-routing");
    }
}
