package br.com.tjosefftech.pdv.report;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/relatorios")
public class ReportsController {

    private final JdbcTemplate jdbc;

    public ReportsController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/vendas")
    public Map<String, Object> vendas(
            @RequestParam(required = false) java.util.UUID lojaId,
            @RequestParam(required = false) String lojas,
            @RequestParam(required = false) String de,
            @RequestParam(required = false) String ate) {
        // TODO: implementar consolidado por loja/tenant com escopo aplicado no servidor
        return Map.of();
    }
}
