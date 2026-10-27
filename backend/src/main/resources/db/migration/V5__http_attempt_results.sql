ALTER TABLE delivery_attempts
    ADD COLUMN http_status INTEGER,
    ADD COLUMN failure_code VARCHAR(32),
    ADD COLUMN duration_ms BIGINT,
    ADD CONSTRAINT attempt_http_status CHECK (http_status BETWEEN 100 AND 599),
    ADD CONSTRAINT attempt_duration CHECK (duration_ms >= 0),
    ADD CONSTRAINT attempt_failure_code CHECK (failure_code IN (
        'HTTP_STATUS', 'REQUEST_TIMEOUT', 'CONNECT_TIMEOUT', 'DNS_ERROR',
        'DESTINATION_BLOCKED', 'NETWORK_ERROR', 'INTERNAL_ERROR', 'RESOURCE_LIMIT')),
    ADD CONSTRAINT attempt_result_metadata CHECK (
        (http_status IS NULL AND failure_code IS NULL AND duration_ms IS NULL)
        OR (duration_ms IS NOT NULL AND (
            (outcome = 'SUCCEEDED' AND http_status IS NOT NULL
                AND http_status BETWEEN 200 AND 299 AND failure_code IS NULL)
            OR (outcome = 'FAILED' AND failure_code IS NOT NULL
                AND (http_status IS NULL OR http_status NOT BETWEEN 200 AND 299))
        ))
    );
