package br.dev.duofinance.category.service;

import br.dev.duofinance.category.model.Category;
import br.dev.duofinance.category.repository.CategoryRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultCategorySeeder {

    private final CategoryRepository categories;
    private final Clock clock;

    public DefaultCategorySeeder(CategoryRepository categories, Clock clock) {
        this.categories = categories;
        this.clock = clock;
    }

    @Transactional
    public void seed(UUID accountId, String locale) {
        Instant now = Instant.now(clock);
        List<Category> defaults = DefaultCategories.forLocale(locale).stream()
                .map(d -> new Category(accountId, d.name(), d.kind(), d.color(), d.icon(), true, now))
                .toList();
        categories.saveAll(defaults);
    }
}
