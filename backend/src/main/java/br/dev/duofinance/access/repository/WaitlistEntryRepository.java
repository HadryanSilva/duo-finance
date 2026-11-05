package br.dev.duofinance.access.repository;

import br.dev.duofinance.access.model.WaitlistEntry;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WaitlistEntryRepository extends JpaRepository<WaitlistEntry, UUID> {}
