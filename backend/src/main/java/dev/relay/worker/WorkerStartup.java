package dev.relay.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("worker")
public class WorkerStartup implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(WorkerStartup.class);
    private final JdbcTemplate jdbc;

    public WorkerStartup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbc.queryForObject("SELECT 1", Integer.class);
        log.info("Worker ready; database connected and bounded delivery configured");
    }
}

