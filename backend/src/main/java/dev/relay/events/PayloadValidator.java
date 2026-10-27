package dev.relay.events;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

final class PayloadValidator {
    static final int MAX_BYTES = 262144;
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private PayloadValidator() {}

    static void validate(byte[] payload) {
        if (payload.length == 0) throw invalidPayload();
        if (payload.length > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.valueOf(413), "Payload is too large");
        }
        try {
            var utf8 = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(payload)).toString();
            var root = JSON.readTree(utf8);
            if (root == null || !root.isObject()) throw invalidPayload();
        } catch (CharacterCodingException | JacksonException invalid) {
            // Parser exception messages may include payload fragments. Do not log or expose them.
            throw invalidPayload();
        }
    }

    private static ResponseStatusException invalidPayload() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expected one UTF-8 JSON object");
    }
}
