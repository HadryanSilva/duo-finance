package br.dev.duofinance.category.repository;

import br.dev.duofinance.category.model.Category;
import br.dev.duofinance.category.model.CategoryKind;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface CategoryRepository extends JpaRepository<Category, UUID> {

    long countByAccountId(UUID accountId);

    boolean existsByAccountIdAndSystemDefaultFalse(UUID accountId);

    Optional<Category> findByIdAndAccountId(UUID id, UUID accountId);

    List<Category> findByAccountIdAndIdIn(UUID accountId, Collection<UUID> ids);

    /** FOR SHARE: a recurring rule referencing the category serializes with its archiving. */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select c from Category c where c.id = :id and c.accountId = :accountId")
    Optional<Category> findByIdAndAccountIdForShare(UUID id, UUID accountId);

    /** FOR UPDATE: archiving waits for rule writes that hold the row FOR SHARE. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Category c where c.id = :id and c.accountId = :accountId")
    Optional<Category> findByIdAndAccountIdForUpdate(UUID id, UUID accountId);

    @Query("""
            select c from Category c
            where c.accountId = :accountId
              and (:kind is null or c.kind = :kind)
              and (:includeArchived = true or c.archivedAt is null)
            order by c.kind, lower(c.name), c.id
            """)
    List<Category> list(UUID accountId, CategoryKind kind, boolean includeArchived);
}
