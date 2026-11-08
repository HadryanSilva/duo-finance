package br.dev.duofinance.user.repository;

import br.dev.duofinance.user.model.IdentityProvider;
import br.dev.duofinance.user.model.UserIdentity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserIdentityRepository extends JpaRepository<UserIdentity, UUID> {

    Optional<UserIdentity> findByProviderAndProviderSubject(IdentityProvider provider, String providerSubject);

    Optional<UserIdentity> findByUserIdAndProvider(UUID userId, IdentityProvider provider);
}
