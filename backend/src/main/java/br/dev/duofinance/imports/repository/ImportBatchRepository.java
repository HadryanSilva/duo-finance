package br.dev.duofinance.imports.repository;

import br.dev.duofinance.imports.model.ImportBatch;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportBatchRepository extends JpaRepository<ImportBatch, UUID> {}
