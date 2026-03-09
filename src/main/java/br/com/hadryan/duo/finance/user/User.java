package br.com.hadryan.duo.finance.user;

import br.com.hadryan.duo.finance.couple.Couple;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@Entity(name = "users")
public class User implements UserDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String firstName;

    private String lastName;

    @Column(unique = true)
    private String email;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "couple_id")
    private Couple couple;

    @Column(length = 30)
    private String provider; // "google" | "local"

    @Column(length = 100)
    private String providerId;

    @Column(length = 500)
    private String avatarUrl;

    @Column(length = 255)
    private String passwordHash;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    protected User() {}

    /** Construtor para login via OAuth2 (Google) */
    public User(String firstName, String lastName, String email,
                String provider, String providerId, String avatarUrl) {
        this.firstName  = firstName;
        this.lastName   = lastName;
        this.email      = email;
        this.provider   = provider;
        this.providerId = providerId;
        this.avatarUrl  = avatarUrl;
    }

    /** Construtor para login local (email + senha) */
    public User(String firstName, String lastName, String email, String passwordHash) {
        this.firstName    = firstName;
        this.lastName     = lastName;
        this.email        = email;
        this.passwordHash = passwordHash;
        this.provider     = "local";
    }

    /** Conveniência para exibição — não persistido. */
    public String getFullName() {
        return firstName + " " + lastName;
    }

    // ── UserDetails ───────────────────────────────────────────────────────────

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of();
    }

    /** Usado pelo Spring Security para autenticação local. */
    @Override
    public String getPassword() {
        return passwordHash;
    }

    /** Username = email no contexto do Spring Security. */
    @Override
    public String getUsername() {
        return email;
    }
}