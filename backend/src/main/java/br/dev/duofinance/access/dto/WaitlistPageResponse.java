package br.dev.duofinance.access.dto;

import br.dev.duofinance.access.model.WaitlistEntry;
import java.util.List;
import org.springframework.data.domain.Page;

/** Stable page shape; a Spring Data {@code PageImpl} is deliberately not serialized directly. */
public record WaitlistPageResponse(
        List<WaitlistEntryResponse> content, int page, int size, long totalElements, int totalPages) {

    public static WaitlistPageResponse of(Page<WaitlistEntry> p) {
        return new WaitlistPageResponse(
                p.getContent().stream().map(WaitlistEntryResponse::of).toList(),
                p.getNumber(),
                p.getSize(),
                p.getTotalElements(),
                p.getTotalPages());
    }
}
