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

    private final BtgExcelParser btgExcelParser;
    private final OfxParser ofxParser;
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

        // XLSX não possui FITID — deduplicação por date+description+amount
        Set<String> existingKeys = loadExistingFallbackKeys(couple.getId());

        List<Transaction> toSave = new ArrayList<>();
        int skipped = 0;

        for (BtgExcelParser.ParsedTransaction pt : parsed) {
            String key = fallbackKey(pt.date(), pt.description(), pt.amount());
            if (existingKeys.contains(key)) {
                skipped++;
                continue;
            }

            Transaction tx = buildTransaction(couple, currentUser);
            tx.setCategory(pt.category());
            tx.setType(pt.type());
            tx.setAmount(pt.amount());
            tx.setDescription(pt.description());
            tx.setDate(pt.date());

            toSave.add(tx);
            existingKeys.add(key);
        }

        transactionRepository.saveAll(toSave);
        log.info("XLSX import — couple={} imported={} skipped={}", couple.getId(), toSave.size(), skipped);

        return new ImportDtos.ImportResult(parsed.size(), toSave.size(), skipped);
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

        // OFX possui FITID — deduplicação primária por externalId
        Set<String> existingExternalIds  = loadExistingExternalIds(couple.getId());
        // Fallback para transações sem FITID (raro, mas possível)
        Set<String> existingFallbackKeys = loadExistingFallbackKeys(couple.getId());

        List<Transaction> toSave = new ArrayList<>();
        int skipped = 0;

        for (OfxParser.ParsedTransaction pt : parsed) {
            if (pt.fitId() != null && !pt.fitId().isBlank()) {
                if (existingExternalIds.contains(pt.fitId())) {
                    skipped++;
                    continue;
                }
            } else {
                String key = fallbackKey(pt.date(), pt.description(), pt.amount());
                if (existingFallbackKeys.contains(key)) {
                    skipped++;
                    continue;
                }
                existingFallbackKeys.add(key);
            }

            Transaction tx = buildTransaction(couple, currentUser);
            tx.setCategory(pt.category());
            tx.setType(pt.type());
            tx.setAmount(pt.amount());
            tx.setDescription(pt.description());
            tx.setDate(pt.date());
            tx.setExternalId(pt.fitId());

            toSave.add(tx);
            if (pt.fitId() != null) existingExternalIds.add(pt.fitId());
        }

        transactionRepository.saveAll(toSave);
        log.info("OFX import — couple={} imported={} skipped={}", couple.getId(), toSave.size(), skipped);

        return new ImportDtos.ImportResult(parsed.size(), toSave.size(), skipped);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Transaction buildTransaction(Couple couple, User currentUser) {
        Transaction tx = new Transaction();
        tx.setCouple(couple);
        tx.setUser(currentUser);
        tx.setCustomCategory(null);
        tx.setRecurring(false);
        return tx;
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

    private Set<String> loadExistingExternalIds(UUID coupleId) {
        return new HashSet<>(transactionRepository.findExternalIdsByCoupleId(coupleId));
    }

    private Set<String> loadExistingFallbackKeys(UUID coupleId) {
        List<Object[]> rows = transactionRepository.findDeduplicationKeys(coupleId);
        Set<String> keys = new HashSet<>();
        for (Object[] row : rows) {
            LocalDate  date        = (LocalDate)   row[0];
            String     description = (String)       row[1];
            BigDecimal amount      = (BigDecimal)   row[2];
            keys.add(fallbackKey(date, description, amount));
        }
        return keys;
    }

    private String fallbackKey(LocalDate date, String description, BigDecimal amount) {
        return date + "|" + (description == null ? "" : description.trim().toLowerCase())
                + "|" + amount.toPlainString();
    }
}