package br.com.hadryan.duo.finance.user;

import br.com.hadryan.duo.finance.user.dto.UserDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Service
public class UserService {

    private final UserRepository repository;

    @Transactional
    public User updateProfile(User user, UserDtos.UpdateProfileRequest request) {
        user.setFirstName(request.firstName());
        user.setLastName(request.lastName());

        // avatarUrl null ou vazio = mantém o atual (não sobrescreve foto do Google, por ex.)
        if (request.avatarUrl() != null && !request.avatarUrl().isBlank()) {
            user.setAvatarUrl(request.avatarUrl());
        }

        return repository.save(user);
    }
}
