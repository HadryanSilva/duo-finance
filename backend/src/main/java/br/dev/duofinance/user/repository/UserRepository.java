package br.dev.duofinance.user.repository;

import br.dev.duofinance.user.model.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String normalizedEmail);
}
