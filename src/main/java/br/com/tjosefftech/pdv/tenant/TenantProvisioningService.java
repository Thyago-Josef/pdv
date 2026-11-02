package br.com.tjosefftech.pdv.tenant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

@Service
public class TenantProvisioningService {

    private final JdbcTemplate controlJdbc;
    private final DataSource controlDataSource;

    public TenantProvisioningService(JdbcTemplate controlJdbc, DataSource controlDataSource) {
        this.controlJdbc = controlJdbc;
        this.controlDataSource = controlDataSource;
    }

    public UUID provisionar(String nome, String dbHost, int dbPort, String dbName, String dbUser, String dbPassword) {
        String adminUrl = "jdbc:postgresql://" + dbHost + ":" + dbPort + "/postgres";
        try (Connection conn = DriverManager.getConnection(adminUrl, "postgres", "postgres");
             PreparedStatement st = conn.prepareStatement("CREATE DATABASE " + dbName)) {
            st.execute();
        } catch (SQLException e) {
            throw new IllegalStateException("Falha ao criar banco do tenant " + dbName, e);
        }
        return UUID.randomUUID();
    }
}
