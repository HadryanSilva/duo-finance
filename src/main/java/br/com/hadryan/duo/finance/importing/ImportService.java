package br.com.hadryan.duo.finance.importing;

import br.com.hadryan.duo.finance.couple.Couple;
import br.com.hadryan.duo.finance.importing.dto.ImportDtos;
import br.com.hadryan.duo.finance.importing.parser.BtgExcelParser;
import br.com.hadryan.duo.finance.importing.parser.OfxParser;
import br.com.hadryan.duo.finance.shared.exception.BusinessException;
import br.com.hadryan.duo.finance.transaction.Transaction;
import br.com.hadryan.duo.finance.transaction.TransactionRepository;
import br.com.hadryan.duo.finance.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImportService {

    private static final long MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024; // 10 MB

    private final BtgExcelParser        btgExcelParser;
    private final OfxParser             ofxParser;
    private final TransactionRepository transactionRepository;

    // ── XLSX (BTG Pactual) ────────────────────────────────────────────────────

    @Transactional
    public ImportDtos.ImportResult importXlsx(MultipartFile file, User currentUser) {
        validateFile(file, ".xlsx");
        Couple couple = requireCouple(currentUser);

        List<BtgExcelParser.ParsedTransaction> parsed;
        try {
            parsed = btgExcelParser.parse(file);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(e.getMessage());
        } catch (IOException e) {
            log.error("Error reading XLSX import file", e);
            throw new BusinessException("Não foi possível ler o arquivo. Verifique se é um XLSX válido.");
        }

        if (parsed.isEmpty()) {
            throw new BusinessException(
                    "Nenhuma transação encontrada no arquivo. " +
                            "Verifique se é um extrato bancário no formato XLSX correto.");
        }

        Set<String> existingHashes = loadExistingHashes(couple.getId());

        List<Transaction> toSave = new ArrayList<>();
        int skipped = 0;

        for (BtgExcelParser.ParsedTransaction pt : parsed) {
            String hash = ImportHashUtil.forXlsx(couple.getId(), pt.date(), pt.amount(), pt.description());

            if (existingHashes.contains(hash)) {
                skipped++;
                continue;
            }

            Transaction tx = buildBase(couple, currentUser);
            tx.setCategory(pt.category());
            tx.setType(pt.type());
            tx.setAmount(pt.amount());
            tx.setDescription(pt.description());
            tx.setDate(pt.date());
            tx.setImportHash(hash);

            toSave.add(tx);
            existingHashes.add(hash);
        }

        List<Transaction> saved = transactionRepository.saveAll(toSave);
        List<ImportDtos.SuspectedDuplicate> suspects = detectSuspects(saved, couple.getId());

        log.info("XLSX import — couple={} imported={} skipped={} suspects={}",
                couple.getId(), saved.size(), skipped, suspects.size());

        return new ImportDtos.ImportResult(parsed.size(), saved.size(), skipped, suspects);
    }

    // ── OFX (qualquer banco) ──────────────────────────────────────────────────

    @Transactional
    public ImportDtos.ImportResult importOfx(MultipartFile file, User currentUser) {
        validateFile(file, ".ofx");
        Couple couple = requireCouple(currentUser);

        List<OfxParser.ParsedTransaction> parsed;
        try {
            parsed = ofxParser.parse(file);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(e.getMessage());
        } catch (IOException e) {
            log.error("Error reading OFX import file", e);
            throw new BusinessException("Não foi possível ler o arquivo. Verifique se é um OFX válido.");
        }

        if (parsed.isEmpty()) {
            throw new BusinessException(
                    "Nenhuma transação encontrada no arquivo. " +
                            "Verifique se é um extrato bancário no formato OFX correto.");
        }

        Set<String> existingHashes = loadExistingHashes(couple.getId());

        List<Transaction> toSave = new ArrayList<>();
        int skipped = 0;

        for (OfxParser.ParsedTransaction pt : parsed) {
            String hash = pt.fitId() != null && !pt.fitId().isBlank()
                    ? ImportHashUtil.forOfx(couple.getId(), pt.fitId())
                    : ImportHashUtil.forXlsx(couple.getId(), pt.date(), pt.amount(), pt.description());

            if (existingHashes.contains(hash)) {
                skipped++;
                continue;
            }

            Transaction tx = buildBase(couple, currentUser);
            tx.setCategory(pt.category());
            tx.setType(pt.type());
            tx.setAmount(pt.amount());
            tx.setDescription(pt.description());
            tx.setDate(pt.date());
            tx.setExternalId(pt.fitId());
            tx.setImportHash(hash);

            toSave.add(tx);
            existingHashes.add(hash);
        }

        List<Transaction> saved = transactionRepository.saveAll(toSave);
        List<ImportDtos.SuspectedDuplicate> suspects = detectSuspects(saved, couple.getId());

        log.info("OFX import — couple={} imported={} skipped={} suspects={}",
                couple.getId(), saved.size(), skipped, suspects.size());

        return new ImportDtos.ImportResult(parsed.size(), saved.size(), skipped, suspects);
    }

    // ── Detecção de prováveis duplicatas ──────────────────────────────────────

    /**
     * For each newly saved transaction, queries existing transactions with the same
     * date + amount + type but a different importHash.
     *
     * This catches cases where the user manually registered a transaction that was
     * later imported, even if the description was typed differently.
     */
    private List<ImportDtos.SuspectedDuplicate> detectSuspects(
            List<Transaction> saved, UUID coupleId) {

        List<ImportDtos.SuspectedDuplicate> suspects = new ArrayList<>();

        for (Transaction imported : saved) {
            List<Transaction> candidates = transactionRepository.findPotentialDuplicates(
                    coupleId,
                    imported.getDate(),
                    imported.getAmount(),
                    imported.getType(),
                    imported.getImportHash()
            );

            for (Transaction existing : candidates) {
                suspects.add(new ImportDtos.SuspectedDuplicate(
                        imported.getId(),
                        imported.getDescription(),
                        existing.getId(),
                        existing.getDescription(),
                        imported.getDate(),
                        imported.getAmount(),
                        imported.getType()
                ));
            }
        }

        return suspects;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Transaction buildBase(Couple couple, User currentUser) {
        Transaction tx = new Transaction();
        tx.setCouple(couple);
        tx.setUser(currentUser);
        tx.setCustomCategory(null);
        tx.setRecurring(false);
        return tx;
    }

    private Set<String> loadExistingHashes(UUID coupleId) {
        return new HashSet<>(transactionRepository.findImportHashesByCoupleId(coupleId));
    }

    private void validateFile(MultipartFile file, String expectedExtension) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("Nenhum arquivo enviado.");
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new BusinessException("Arquivo muito grande. O tamanho máximo permitido é 10 MB.");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(expectedExtension)) {
            throw new BusinessException(
                    "Formato inválido. Envie um arquivo " + expectedExtension + " exportado pelo seu banco.");
        }
    }

    private Couple requireCouple(User user) {
        if (user.getCouple() == null) {
            throw new BusinessException("Você ainda não pertence a nenhuma conta de casal.");
        }
        return user.getCouple();
    }
}