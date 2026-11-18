package br.dev.duofinance.account.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/** Partial update: each field is optional, but at least one must be present. */
public record UpdateAccountRequest(
        // The pattern passes for null and rejects blank, so the error is reported on the "name" field.
        @Nullable @Pattern(regexp = "(?s).*\\S.*") @Size(max = 60)
        String name,

        @Nullable Boolean aiSuggestionsEnabled) {

    @JsonIgnore
    @AssertTrue
    public boolean isAnyFieldPresent() {
        return name != null || aiSuggestionsEnabled != null;
    }
}
