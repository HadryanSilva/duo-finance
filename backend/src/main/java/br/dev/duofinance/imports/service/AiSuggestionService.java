package br.dev.duofinance.imports.service;

import br.dev.duofinance.imports.ai.MerchantCategorizer;
import br.dev.duofinance.imports.ai.MerchantCategorizer.CategoryOption;
import br.dev.duofinance.imports.ai.MerchantCategorizer.Merchant;
import br.dev.duofinance.shared.config.AppProperties;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Fills the preview's remaining unknown card merchants with AI category suggestions. Deliberately not transactional:
 * the (slow, external) call must not hold a database connection or transaction open.
 */
@Service
public class AiSuggestionService {

    private final JdbcTemplate jdbc;
    private final MerchantCategorizer categorizer;
    private final AppProperties props;

    public AiSuggestionService(JdbcTemplate jdbc, MerchantCategorizer categorizer, AppProperties props) {
        this.jdbc = jdbc;
        this.categorizer = categorizer;
        this.props = props;
    }

    public ImportPlan apply(UUID accountId, ImportPlan plan) {
        Map<String, String> candidates = candidates(plan);
        if (candidates.isEmpty() || !enabled(accountId)) {
            return plan;
        }
        List<CategoryOption> categories = expenseCategories(accountId);
        if (categories.isEmpty()) {
            return plan;
        }
        List<Merchant> merchants = new ArrayList<>();
        candidates.forEach((key, description) -> merchants.add(new Merchant(key, description)));
        Map<String, UUID> answers = categorizer.categorize(accountId, merchants, categories);
        if (answers == null || answers.isEmpty()) {
            return plan;
        }
        Set<UUID> allowed = new HashSet<>();
        categories.forEach(c -> allowed.add(c.id()));
        Map<String, UUID> valid = new LinkedHashMap<>();
        answers.forEach((key, id) -> {
            if (key != null && id != null && allowed.contains(id) && candidates.containsKey(key)) {
                valid.put(key, id);
            }
        });
        return valid.isEmpty() ? plan : ImportPlanner.applyAiSuggestions(plan, valid);
    }

    /** descriptionKey -> description of the first row with that key, in order of appearance, capped at max-items. */
    private Map<String, String> candidates(ImportPlan plan) {
        int max = props.ai() == null ? 0 : props.ai().maxItems();
        Map<String, String> out = new LinkedHashMap<>();
        for (PlannedRow row : plan.rows()) {
            if (out.size() >= max) {
                break;
            }
            if (row.needsAi()) {
                out.putIfAbsent(row.descriptionKey(), row.description());
            }
        }
        return out;
    }

    private boolean enabled(UUID accountId) {
        Boolean flag = jdbc.query(
                "SELECT ai_suggestions_enabled FROM accounts WHERE id = ?",
                rs -> rs.next() ? rs.getBoolean(1) : null,
                accountId);
        return Boolean.TRUE.equals(flag);
    }

    private List<CategoryOption> expenseCategories(UUID accountId) {
        return jdbc.query(
                """
                SELECT id, name FROM categories
                WHERE account_id = ? AND kind = 'EXPENSE' AND archived_at IS NULL
                  AND NOT (system_default AND name IN ('Outros', 'Other'))
                ORDER BY name
                """, (rs, i) -> new CategoryOption(rs.getObject("id", UUID.class), rs.getString("name")), accountId);
    }
}
