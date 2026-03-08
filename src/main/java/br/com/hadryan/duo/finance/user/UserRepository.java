package br.com.hadryan.duo.finance.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    List<User> findByCoupleId(UUID coupleId);

    boolean existsByEmail(String email);

    long countByCoupleId(UUID coupleId);

    // JOIN FETCH evita LazyInitializationException ao acessar user.getCouple()
    // fora de uma transação (ex: no JwtAuthFilter)
    @Query("SELECT u FROM users u LEFT JOIN FETCH u.couple WHERE u.id = :id")
    Optional<User> findByIdWithCouple(UUID id);
}
