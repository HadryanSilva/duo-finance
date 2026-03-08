package br.com.hadryan.duo.finance.auth.oauth;

import br.com.hadryan.duo.finance.user.User;
import br.com.hadryan.duo.finance.user.UserRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

@RequiredArgsConstructor
@Service
public class DuoOAuth2UserService extends OidcUserService {

    private final UserRepository userRepository;

    @Override
    @Transactional
    public OidcUser loadUser(OidcUserRequest request) throws OAuth2AuthenticationException {

        // 1. Delega ao OidcUserService — carrega claims do OpenID Connect (id_token + userinfo)
        OidcUser oidcUser = super.loadUser(request);

        String provider   = request.getClientRegistration().getRegistrationId(); // "google"
        String providerId = oidcUser.getSubject();    // claim "sub"
        String email      = oidcUser.getEmail();
        String firstName  = oidcUser.getGivenName();  // claim "given_name"
        String lastName   = oidcUser.getFamilyName(); // claim "family_name"
        String avatarUrl  = oidcUser.getPicture();    // claim "picture"

        // 2. Upsert: busca pelo e-mail, cria se não existir
        User user = userRepository.findByEmail(email)
                .map(existing -> updateIfChanged(existing, firstName, lastName, avatarUrl, providerId))
                .orElseGet(() -> createUser(email, firstName, lastName, provider, providerId, avatarUrl));

        // 3. Retorna nosso wrapper que implementa OidcUser
        return new AuthenticatedOAuth2User(oidcUser, user);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private User updateIfChanged(User user, String firstName, String lastName,
                                 String avatarUrl, String providerId) {
        boolean changed = false;

        if (firstName != null && !firstName.equals(user.getFirstName())) {
            user.setFirstName(firstName);
            changed = true;
        }
        if (lastName != null && !lastName.equals(user.getLastName())) {
            user.setLastName(lastName);
            changed = true;
        }
        if (avatarUrl != null && !avatarUrl.equals(user.getAvatarUrl())) {
            user.setAvatarUrl(avatarUrl);
            changed = true;
        }
        if (!user.getProviderId().equals(providerId)) {
            user.setProviderId(providerId);
            changed = true;
        }

        return changed ? userRepository.save(user) : user;
    }

    private User createUser(String email, String firstName, String lastName,
                            String provider, String providerId, String avatarUrl) {
        return userRepository.save(
                new User(firstName, lastName != null ? lastName : "", email, provider, providerId, avatarUrl)
        );
    }

}
