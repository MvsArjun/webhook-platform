package dev.relay;

import java.net.URI;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

import dev.relay.delivery.ClaimedDelivery;
import dev.relay.delivery.CompletionOutcome;
import dev.relay.delivery.DeliveryClaimService;
import dev.relay.delivery.DeliveryProcessor;
import dev.relay.delivery.DeliveryResult;
import dev.relay.delivery.DeliveryLimits;
import dev.relay.workspaces.WorkspaceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Workspace capacity is shared database state, rather than a separate allowance in each worker. */
class WorkspaceLimitsIT extends HttpIntegrationTestSupport {
    private static final Duration LEASE = Duration.ofMinutes(2);
    private final List<UUID> accounts = new ArrayList<>();
    private final List<ConfigurableApplicationContext> contexts = new ArrayList<>();

    @AfterEach
    void removeFixtures() {
        contexts.forEach(ConfigurableApplicationContext::close);
        for (UUID accountId : accounts) {
            for (String table : List.of("delivery_attempts", "deliveries", "events", "endpoint_signing_keys", "endpoints", "api_keys")) {
                jdbc.update("DELETE FROM " + table + " WHERE workspace_id IN "
                        + "(SELECT id FROM workspaces WHERE owner_account_id = ?)", accountId);
            }
            jdbc.update("DELETE FROM workspaces WHERE owner_account_id = ?", accountId);
            jdbc.update("DELETE FROM accounts WHERE id = ?", accountId);
        }
    }

    @Test
    void independentWorkersAndConcurrentClaimersShareOneWorkspaceAllowance() throws Exception {
        var fixture = fixture(2, 12, URI.create("https://receiver.company.com/hook"));
        var services = List.of(worker(null).getBean(DeliveryClaimService.class),
                worker(null).getBean(DeliveryClaimService.class));
        var ready = new CountDownLatch(8);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<java.util.concurrent.Future<List<ClaimedDelivery>>>();
            for (int index = 0; index < 8; index++) {
                var service = services.get(index % services.size());
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Claim start timed out");
                    return service.claim(2, LEASE);
                }));
            }
            try { assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); }
            finally { start.countDown(); }
            var acquired = new ArrayList<ClaimedDelivery>();
            for (var future : futures) acquired.addAll(future.get(15, TimeUnit.SECONDS));
            // A worker may skip a busy workspace row, but nobody can spend another worker's allowance.
            if (acquired.size() < 2) acquired.addAll(services.getFirst().claim(2, LEASE));
            assertThat(acquired).hasSize(2);
            assertThat(acquired.stream().map(ClaimedDelivery::deliveryId).toList()).doesNotHaveDuplicates();
            assertThat(active(fixture)).isEqualTo(2);
            assertThat(attemptCount(fixture)).isEqualTo(2);
            for (var service : services) assertThat(service.claim(100, LEASE)).isEmpty();
        }
    }

    @Test
    void aFullWorkspaceDoesNotPreventAnotherWorkspaceFromMakingProgress() {
        var service = api.getBean(DeliveryClaimService.class);
        var full = fixture(1, 3, URI.create("https://receiver.company.com/full"));
        assertThat(service.claim(1, LEASE)).hasSize(1);
        var other = fixture(2, 3, URI.create("https://receiver.company.com/other"));

        var next = service.claim(100, LEASE);
        assertThat(next).hasSize(2);
        assertThat(next.stream().map(ClaimedDelivery::workspaceId).toList())
                .containsOnly(other.workspaceId());
        assertThat(active(full)).isEqualTo(1);
        assertThat(active(other)).isEqualTo(2);
    }

    @Test
    void completingOneClaimFreesExactlyOneSlotAndDuplicateCompletionFreesNone() {
        var fixture = fixture(2, 4, URI.create("https://receiver.company.com/complete"));
        var service = api.getBean(DeliveryClaimService.class);
        var initial = service.claim(100, LEASE);
        assertThat(initial).hasSize(2);
        var completed = initial.getFirst();
        assertThat(service.complete(completed, new DeliveryResult(CompletionOutcome.SUCCEEDED, 204, null, 1))).isTrue();
        assertThat(active(fixture)).isEqualTo(1);
        var replacement = service.claim(100, LEASE);
        assertThat(replacement).hasSize(1);
        assertThat(initial.stream().map(ClaimedDelivery::deliveryId).toList())
                .doesNotContain(replacement.getFirst().deliveryId());
        assertThat(service.complete(completed, new DeliveryResult(CompletionOutcome.SUCCEEDED, 204, null, 1))).isFalse();
        assertThat(active(fixture)).isEqualTo(2);
        assertThat(service.claim(100, LEASE)).isEmpty();
    }

    @Test
    void expiryCountsUntilRecoveryAndStaleCompletionCannotFreeItsNewClaim() {
        var fixture = fixture(1, 1, URI.create("https://receiver.company.com/recover"));
        var service = api.getBean(DeliveryClaimService.class);
        var original = service.claim(1, LEASE).getFirst();
        jdbc.update("UPDATE deliveries SET lease_expires_at = clock_timestamp() - interval '1 second' WHERE id = ?",
                original.deliveryId());
        assertThat(active(fixture)).isEqualTo(1);
        assertThat(service.claim(1, LEASE)).as("expiry alone does not spend recovered capacity").isEmpty();
        assertThat(service.recoverExpired(100)).isEqualTo(1);
        assertThat(active(fixture)).isZero();
        jdbc.update("UPDATE deliveries SET next_attempt_at = clock_timestamp() - interval '1 second' WHERE id = ?",
                original.deliveryId());
        var current = service.claim(1, LEASE).getFirst();
        assertThat(current.deliveryId()).isEqualTo(original.deliveryId());
        assertThat(current.generation()).isEqualTo(original.generation() + 1);
        assertThat(service.complete(original, new DeliveryResult(CompletionOutcome.SUCCEEDED, 200, null, 1))).isFalse();
        assertThat(active(fixture)).isEqualTo(1);
        assertThat(service.claim(1, LEASE)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT outcome FROM delivery_attempts WHERE id = ?", String.class,
                original.attemptId())).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT outcome FROM delivery_attempts WHERE id = ?", String.class,
                current.attemptId())).isEqualTo("IN_PROGRESS");
    }

    @Test
    void failedClaimTransactionLeavesAllWorkspaceCapacityAvailable() {
        var fixture = fixture(2, 3, URI.create("https://receiver.company.com/rollback"));
        var service = api.getBean(DeliveryClaimService.class);
        String constraint = "quota_rollback_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("ALTER TABLE delivery_attempts ADD CONSTRAINT " + constraint
                + " CHECK (workspace_id <> '" + fixture.workspaceId() + "'::uuid) NOT VALID");
        try {
            assertThatThrownBy(() -> service.claim(2, LEASE)).isInstanceOf(DataIntegrityViolationException.class);
            assertThat(active(fixture)).isZero();
            assertThat(attemptCount(fixture)).isZero();
            assertThat(jdbc.queryForObject("SELECT sum(run_attempts) FROM deliveries WHERE workspace_id = ?",
                    Long.class, fixture.workspaceId())).isZero();
        } finally {
            jdbc.execute("ALTER TABLE delivery_attempts DROP CONSTRAINT " + constraint);
        }
        assertThat(worker(null).getBean(DeliveryClaimService.class).claim(100, LEASE)).hasSize(2);
        assertThat(active(fixture)).isEqualTo(2);
    }

    @Test
    void databaseRejectsWorkspaceLimitsOutsideTheSupportedRange() {
        var fixture = fixture(1, 0, URI.create("https://receiver.company.com/bounds"));
        for (int value : List.of(0, 33)) {
            assertThatThrownBy(() -> jdbc.update("UPDATE workspaces SET delivery_concurrency_limit = ? WHERE id = ?",
                    value, fixture.workspaceId())).isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThat(jdbc.update("UPDATE workspaces SET delivery_concurrency_limit = 32 WHERE id = ?",
                fixture.workspaceId())).isEqualTo(1);
    }

    @Test
    void newWorkspacesPersistTheConfiguredAllowanceAndResourceBoundsAreValidated() {
        var fixture = fixture(4, 0, URI.create("https://receiver.company.com/configuration"));
        var configured = worker(null, "--relay.delivery.workspace-concurrency-limit=2")
                .getBean(WorkspaceService.class).create(fixture.accountId(), "Configured allowance");
        assertThat(configured.deliveryConcurrencyLimit()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT delivery_concurrency_limit FROM workspaces WHERE id = ?",
                Integer.class, configured.id())).isEqualTo(2);
        assertThat(api.getBean(WorkspaceService.class).create(fixture.accountId(), "Default allowance")
                .deliveryConcurrencyLimit()).isEqualTo(4);
        for (int value : List.of(0, 33)) {
            assertThatThrownBy(() -> new DeliveryLimits(value, 4096)).isInstanceOf(IllegalArgumentException.class);
        }
        for (int value : List.of(-1, 16385)) {
            assertThatThrownBy(() -> new DeliveryLimits(4, value)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(new DeliveryLimits(1, 0).responseCaptureBytes()).isZero();
        assertThat(new DeliveryLimits(32, 16384).workspaceConcurrencyLimit()).isEqualTo(32);
    }

    @Test
    void workspaceQuotaLockIsReleasedBeforeAnyReceiverWait() throws Exception {
        var release = new CountDownLatch(1);
        try (var receiver = new ControllableReceiver(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            receiver.block(release);
            var fixture = fixture(1, 1, receiver.url());
            var context = worker(receiver.url());
            var claim = context.getBean(DeliveryClaimService.class).claim(1, LEASE).getFirst();
            var processing = executor.submit(() -> context.getBean(DeliveryProcessor.class).process(claim));
            try {
                assertThat(receiver.next(Duration.ofSeconds(5))).isNotNull();
                try (var connection = api.getBean(DataSource.class).getConnection()) {
                    connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                    connection.setAutoCommit(false);
                    try {
                        try (var statement = connection.createStatement()) {
                            statement.execute("SET LOCAL lock_timeout = '500ms'");
                        }
                        try (var statement = connection.prepareStatement("SELECT id FROM workspaces WHERE id = ? FOR UPDATE")) {
                            statement.setObject(1, fixture.workspaceId());
                            try (var rows = statement.executeQuery()) { assertThat(rows.next()).isTrue(); }
                        }
                    } finally { connection.rollback(); }
                }
            } finally { release.countDown(); }
            processing.get(10, TimeUnit.SECONDS);
            assertThat(active(fixture)).isZero();
        } finally { release.countDown(); }
    }

    private ConfigurableApplicationContext worker(URI receiver, String... extraArguments) {
        var builder = application().profiles(receiver == null ? new String[]{"worker"} : new String[]{"worker", "development"})
                .web(WebApplicationType.NONE);
        var arguments = new ArrayList<>(List.of("--spring.main.keep-alive=false", "--relay.worker.enabled=false"));
        if (receiver != null) {
            arguments.add("--relay.delivery.allow-local-receiver=true");
            arguments.add("--relay.delivery.local-receiver-url=" + receiver);
        }
        arguments.addAll(List.of(extraArguments));
        var context = builder.run(arguments.toArray(String[]::new));
        contexts.add(context);
        return context;
    }

    private Fixture fixture(int limit, int jobs, URI endpoint) {
        UUID account = UUID.randomUUID(), workspace = UUID.randomUUID(), endpointId = UUID.randomUUID();
        accounts.add(account);
        jdbc.update("INSERT INTO accounts (id,email,password_hash) VALUES (?, ?, 'test-only-noncredential')",
                account, account + "@example.test");
        jdbc.update("INSERT INTO workspaces (id,owner_account_id,name,delivery_concurrency_limit) VALUES (?, ?, 'Quota fixture', ?)",
                workspace, account, limit);
        jdbc.update("INSERT INTO endpoints (id,workspace_id,url) VALUES (?, ?, ?)", endpointId, workspace, endpoint.toString());
        for (int index = 0; index < jobs; index++) {
            UUID event = UUID.randomUUID();
            jdbc.update("INSERT INTO events (id,workspace_id,endpoint_id,payload,request_digest) VALUES (?, ?, ?, ?, ?)",
                    event, workspace, endpointId, new byte[]{123, 125}, new byte[32]);
            jdbc.update("INSERT INTO deliveries (id,workspace_id,event_id) VALUES (?, ?, ?)", UUID.randomUUID(), workspace, event);
        }
        return new Fixture(account, workspace);
    }

    private int active(Fixture fixture) {
        return jdbc.queryForObject("SELECT count(*) FROM deliveries WHERE workspace_id = ? AND status = 'IN_FLIGHT'",
                Integer.class, fixture.workspaceId());
    }

    private int attemptCount(Fixture fixture) {
        return jdbc.queryForObject("SELECT count(*) FROM delivery_attempts WHERE workspace_id = ?",
                Integer.class, fixture.workspaceId());
    }

    private record Fixture(UUID accountId, UUID workspaceId) {}
}
