package br.com.tjosefftech.pdv.tenant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

@Configuration
public class TenantDataSourceConfig {

    private final DataSource controlDataSource;

    public TenantDataSourceConfig(DataSource controlDataSource) {
        this.controlDataSource = controlDataSource;
    }

    @Bean
    @Primary
    public DataSource dataSource() {
        return new TenantRoutingDataSource(controlDataSource);
    }

    @Bean
    public DataSource controlDataSource(
            @Value("${app.control.datasource.url}") String url,
            @Value("${app.control.datasource.username}") String username,
            @Value("${app.control.datasource.password}") String password) {
        com.zaxxer.hikari.HikariDataSource ds = new com.zaxxer.hikari.HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setMaximumPoolSize(5);
        return ds;
    }
}
