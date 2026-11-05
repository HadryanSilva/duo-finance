package br.dev.duofinance.category.repository;

import br.dev.duofinance.category.model.Category;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, UUID> {

    long countByAccountId(UUID accountId);
}
