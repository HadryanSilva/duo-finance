package br.dev.duofinance.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.dev.duofinance.account.repository.AccountMemberRepository;
import br.dev.duofinance.account.repository.AccountRepository;
import br.dev.duofinance.account.repository.InvitationRepository;
import br.dev.duofinance.account.service.AccountDeletionService;
import br.dev.duofinance.auth.service.AuthSessionService;
import br.dev.duofinance.shared.error.ApiException;
import br.dev.duofinance.shared.error.ErrorCode;
import br.dev.duofinance.shared.security.CurrentUser;
import br.dev.duofinance.user.model.UserRole;
import br.dev.duofinance.user.repository.UserIdentityRepository;
import br.dev.duofinance.user.repository.UserRepository;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/** A user deleted between the token check and the service call is a stale session, not a wrong password. */
class MeServiceTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-7000-8000-0000000000a1");
    private static final CurrentUser CURRENT = new CurrentUser(USER_ID, ACCOUNT_ID, UserRole.USER);

    private final UserRepository users = mock(UserRepository.class);
    private final MeService service = new MeService(
            users,
            mock(UserIdentityRepository.class),
            mock(PasswordEncoder.class),
            mock(AuthSessionService.class),
            mock(AccountRepository.class),
            mock(AccountMemberRepository.class),
            mock(InvitationRepository.class),
            mock(AccountDeletionService.class),
            Clock.systemUTC());

    @Test
    void get_userDeletedMeanwhile_isSessionStale() {
        when(users.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(CURRENT))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.SESSION_STALE));
    }

    @Test
    void delete_userDeletedMeanwhile_isSessionStale() {
        when(users.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(CURRENT, "secret123"))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.SESSION_STALE));
    }
}
