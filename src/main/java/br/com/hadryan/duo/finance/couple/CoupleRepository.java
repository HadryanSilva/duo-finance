package br.com.hadryan.duo.finance.couple;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CoupleRepository extends JpaRepository<Couple, UUID> {

    Optional<Couple> findByInviteToken(String inviteToken);

}
