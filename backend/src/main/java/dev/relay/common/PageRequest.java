package dev.relay.common;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public record PageRequest(int limit, int offset) {
    public PageRequest {
        if (limit < 1 || limit > 100 || offset < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid pagination");
        }
    }
}
