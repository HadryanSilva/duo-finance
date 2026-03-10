package br.com.hadryan.duo.finance.user;

import br.com.hadryan.duo.finance.auth.dto.AuthDtos;
import br.com.hadryan.duo.finance.shared.exception.BusinessException;
import br.com.hadryan.duo.finance.user.dto.UserDtos;
import com.cloudinary.Cloudinary;
import com.cloudinary.Transformation;
import com.cloudinary.utils.ObjectUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@RequiredArgsConstructor
@Service
public class UserService {

    private final UserRepository userRepository;
    private final Cloudinary     cloudinary;

    // ── Editar nome ───────────────────────────────────────────────────────────

    @Transactional
    public AuthDtos.UserInfo updateProfile(UserDtos.UpdateProfileRequest request, User currentUser) {
        currentUser.setFirstName(request.firstName().trim());
        currentUser.setLastName(request.lastName().trim());
        User saved = userRepository.save(currentUser);
        return toUserInfo(saved);
    }

    // ── Upload de avatar → Cloudinary ─────────────────────────────────────────

    @Transactional
    public UserDtos.UploadAvatarResponse uploadAvatar(MultipartFile file, User currentUser) {
        validateImageFile(file);

        try {
            // Faz upload no Cloudinary.
            // public_id fixo por usuário garante que o arquivo anterior é sobrescrito
            // (sem acumular arquivos órfãos na conta).
            String publicId = "duo-finance/avatars/" + currentUser.getId();

            Transformation transformation = new Transformation()
                    .width(200).height(200)
                    .crop("fill")
                    .gravity("face");

            @SuppressWarnings("unchecked")
            Map<String, Object> result = cloudinary.uploader().upload(
                    file.getBytes(),
                    ObjectUtils.asMap(
                            "public_id",      publicId,
                            "overwrite",      true,
                            "resource_type",  "image",
                            "transformation", transformation
                    )
            );

            String avatarUrl = (String) result.get("secure_url");

            currentUser.setAvatarUrl(avatarUrl);
            userRepository.save(currentUser);

            return new UserDtos.UploadAvatarResponse(avatarUrl);

        } catch (IOException e) {
            throw new BusinessException("Falha ao fazer upload da imagem. Tente novamente.");
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void validateImageFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("Nenhum arquivo enviado.");
        }

        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new BusinessException("Apenas imagens são permitidas (JPEG, PNG, WEBP).");
        }

        // Limite de 5 MB
        long maxBytes = 5L * 1024 * 1024;
        if (file.getSize() > maxBytes) {
            throw new BusinessException("A imagem deve ter no máximo 5 MB.");
        }
    }

    private AuthDtos.UserInfo toUserInfo(User user) {
        return new AuthDtos.UserInfo(
                user.getId().toString(),
                user.getFirstName(),
                user.getLastName(),
                user.getEmail(),
                user.getAvatarUrl(),
                user.getCouple() != null ? user.getCouple().getId().toString() : null
        );
    }
}