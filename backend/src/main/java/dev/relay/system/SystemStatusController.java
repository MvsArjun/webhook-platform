package dev.relay.system;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!worker")
public class SystemStatusController {
    private final JdbcTemplate jdbc;

    public SystemStatusController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/system/status")
    public SystemStatus status() {
        jdbc.queryForObject("SELECT 1", Integer.class);
        return SystemStatus.connected();
    }
}

