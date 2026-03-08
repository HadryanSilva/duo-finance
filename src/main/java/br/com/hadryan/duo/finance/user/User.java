package br.com.hadryan.duo.finance.user;

import br.com.hadryan.duo.finance.couple.Couple;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@Entity(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String firstName;

    private String lastName;

    @Column(unique = true)
    private String email;

    private String password;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "couple_id")
    private Couple couple;

    @Column(length = 30)
    private String provider; // "google" | "github"

    @Column(length = 100)
    private String providerId;

    @Column(length = 500)
    private String avatarUrl;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    protected User() {}

    public User(String firstName, String lastName, String email, String provider, String providerId, String avatarUrl) {
        this.firstName  = firstName;
        this.lastName  = lastName;
        this.email      = email;
        this.provider   = provider;
        this.providerId = providerId;
        this.avatarUrl  = avatarUrl;
    }

}
