package br.dev.duofinance.access.service;

import br.dev.duofinance.access.dto.WaitlistEntryResponse;
import br.dev.duofinance.access.dto.WaitlistPageResponse;
import br.dev.duofinance.access.model.WaitlistEntry;
import br.dev.duofinance.access.model.WaitlistStatus;
import br.dev.duofinance.access.repository.WaitlistEntryRepository;
import br.dev.duofinance.shared.config.AppProperties;
import br.dev.duofinance.shared.email.EmailRequestedEvent;
import br.dev.duofinance.shared.email.EmailTemplate;
import br.dev.duofinance.shared.error.ApiException;
import br.dev.duofinance.shared.error.ErrorCode;
import br.dev.duofinance.shared.token.SecureTokens;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WaitlistService {

    static final Duration CODE_TTL = Duration.ofDays(30);

    private final WaitlistEntryRepository entries;
    private final AccessCodeService accessCodes;
    private final ApplicationEventPublisher events;
    private final AppProperties props;
    private final Clock clock;

    public WaitlistService(
            WaitlistEntryRepository entries,
            AccessCodeService accessCodes,
            ApplicationEventPublisher events,
            AppProperties props,
            Clock clock) {
        this.entries = entries;
        this.accessCodes = accessCodes;
        this.events = events;
        this.props = props;
        this.clock = clock;
    }

    /**
     * Idempotent: an existing entry is never touched. Deliberately not transactional, so a unique violation from a
     * concurrent duplicate insert only fails its own short repository transaction and is swallowed here.
     */
    public void join(String email, String locale) {
        String normalized = SecureTokens.normalizeEmail(email);
        if (entries.existsByEmail(normalized)) {
            return;
        }
        try {
            entries.saveAndFlush(new WaitlistEntry(normalized, locale, Instant.now(clock)));
        } catch (DataIntegrityViolationException duplicate) {
            // Lost the race against a concurrent join of the same e-mail; the entry already exists.
        }
    }

    @Transactional(readOnly = true)
    public WaitlistPageResponse list(WaitlistStatus status, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by("createdAt", "id"));
        return WaitlistPageResponse.of(
                status == null ? entries.findAll(pageable) : entries.findByStatus(status, pageable));
    }

    @Transactional
    public WaitlistEntryResponse approve(UUID id, UUID adminId) {
        WaitlistEntry entry = lock(id);
        if (entry.getStatus() == WaitlistStatus.APPROVED && entry.getAccessCodeId() != null) {
            accessCodes.revoke(entry.getAccessCodeId());
        }
        Instant now = Instant.now(clock);
        var generated = accessCodes.generate(entry.getEmail(), 1, now.plus(CODE_TTL), "waitlist", adminId);
        entry.setStatus(WaitlistStatus.APPROVED);
        entry.setDecidedAt(now);
        entry.setAccessCodeId(generated.id());
        String base = props.publicBaseUrl().toString().replaceAll("/+$", "");
        events.publishEvent(new EmailRequestedEvent(
                entry.getEmail(),
                EmailTemplate.ACCESS_CODE,
                entry.getLocale(),
                Map.of("code", generated.plainCode(), "link", base + "/register?code=" + generated.plainCode())));
        return WaitlistEntryResponse.of(entry);
    }

    /** Rejecting an approved entry revokes the access code already e-mailed to it. */
    @Transactional
    public WaitlistEntryResponse reject(UUID id) {
        WaitlistEntry entry = lock(id);
        if (entry.getStatus() == WaitlistStatus.APPROVED && entry.getAccessCodeId() != null) {
            accessCodes.revoke(entry.getAccessCodeId());
        }
        entry.setStatus(WaitlistStatus.REJECTED);
        entry.setDecidedAt(Instant.now(clock));
        return WaitlistEntryResponse.of(entry);
    }

    private WaitlistEntry lock(UUID id) {
        return entries.findByIdForUpdate(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }
}
