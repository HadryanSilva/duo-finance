package br.com.hadryan.duo.finance.importing;

import br.com.hadryan.duo.finance.couple.Couple;
import br.com.hadryan.duo.finance.importing.dto.ImportDtos;
import br.com.hadryan.duo.finance.importing.parser.BtgExcelParser;
import br.com.hadryan.duo.finance.shared.exception.BusinessException;
import br.com.hadryan.duo.finance.transaction.Transaction;
import br.com.hadryan.duo.finance.transaction.TransactionRepository;
import br.com.hadryan.duo.finance.user.User;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImportService {

    private static final long MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024; // 10 MB

    private final BtgExcelParser        parser;
    private final TransactionRepository transactionRepository;

    @Transactional
    public ImportDtos.ImportResult importBtg(MultipartFile file, User currentUser) {
        validateFile(file);

        Couple couple = requireCouple(currentUser);

        List<BtgExcelParser.ParsedTransaction> parsed;
        try {
            parsed = parser.parse(file);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(e.getMessage());
        } catch (IOException e) {
            log.error("Error reading BTG import file", e);
            throw new BusinessException("Não foi possível ler o arquivo. Verifique se ele é um XLSX válido.");
        }

        if (parsed.isEmpty()) {
            throw new BusinessException(
                    "Nenhuma transação foi encontrada no arquivo. " +
                            "Verifique se ele é um extrato do BTG Pactual no formato correto.");
        }

        // Load deduplication keys: existing date+description+amount for this couple
        Set<String> existingKeys = loadExistingKeys(couple.getId());

        List<Transaction> toSave = new ArrayList<>();
        int skipped = 0;

        for (BtgExcelParser.ParsedTransaction pt : parsed) {
            String key = deduplicationKey(pt.date(), pt.description(), pt.amount());

            if (existingKeys.contains(key)) {
                skipped++;
                continue;
            }

            Transaction tx = new Transaction();
            tx.setCouple(couple);
            tx.setUser(currentUser);
            tx.setCategory(pt.category());
            tx.setCustomCategory(null);
            tx.setType(pt.type());
            tx.setAmount(pt.amount());
            tx.setDescription(pt.description());
            tx.setDate(pt.date());
            tx.setRecurring(false);

            toSave.add(tx);
            existingKeys.add(key); // prevents duplicates within the same file
        }

        transactionRepository.saveAll(toSave);

        log.info("BTG import — couple={} user={} imported={} skipped={}",
                couple.getId(), currentUser.getId(), toSave.size(), skipped);

        return new ImportDtos.ImportResult(parsed.size(), toSave.size(), skipped);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("Nenhum arquivo foi enviado.");
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new BusinessException("Arquivo muito grande. O tamanho máximo permitido é de 10 MB.");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".xlsx")) {
            throw new BusinessException("Formato inválido. Envie um arquivo .xlsx exportado do BTG Pactual.");
        }
    }

    private Couple requireCouple(User user) {
        if (user.getCouple() == null) {
            throw new BusinessException("Você ainda não pertence a nenhuma conta de casal.");
        }
        return user.getCouple();
    }

    private Set<String> loadExistingKeys(UUID coupleId) {
        List<Object[]> rows = transactionRepository.findDeduplicationKeys(coupleId);
        Set<String> keys = new HashSet<>();
        for (Object[] row : rows) {
            LocalDate  date        = (LocalDate)   row[0];
            String     description = (String)       row[1];
            BigDecimal amount      = (BigDecimal)   row[2];
            keys.add(deduplicationKey(date, description, amount));
        }
        return keys;
    }

    private String deduplicationKey(LocalDate date, String description, BigDecimal amount) {
        return date + "|" + (description == null ? "" : description.trim().toLowerCase()) + "|" + amount.toPlainString();
    }
}
