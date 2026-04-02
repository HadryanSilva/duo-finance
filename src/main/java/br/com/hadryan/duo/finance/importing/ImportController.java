package br.com.hadryan.duo.finance.importing;

import br.com.hadryan.duo.finance.importing.dto.ImportDtos;
import br.com.hadryan.duo.finance.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/imports")
@RequiredArgsConstructor
public class ImportController {

    private final ImportService service;

    /**
     * POST /api/imports/btg
     *
     * Receives a BTG Pactual XLSX statement via multipart/form-data (field "file"),
     * parses it, deduplicates and persists the found transactions.
     *
     * Returns a summary with total found, imported and skipped.
     */
    @PostMapping(value = "/btg", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImportDtos.ImportResult> importBtg(
            @RequestPart("file") MultipartFile file,
            @AuthenticationPrincipal User currentUser
    ) {
        return ResponseEntity.ok(service.importBtg(file, currentUser));
    }
}
