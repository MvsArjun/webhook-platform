package dev.relay.delivery;

import com.fasterxml.jackson.annotation.JsonCreator;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

/** A run generation is mandatory so delayed duplicates cannot replay a later failed run. */
public record ReplayRequest(@NotNull @Min(0) Long expectedReplayGeneration) {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static ReplayRequest fromJson(JsonNode body) {
        JsonNode generation = body != null && body.isObject() ? body.get("expectedReplayGeneration") : null;
        if (generation == null || !generation.isIntegralNumber() || !generation.canConvertToLong()) {
            throw new IllegalArgumentException("An integer replay generation is required");
        }
        return new ReplayRequest(generation.asLong());
    }
}
