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
     * POST /api/imports/xlsx
     * Imports a BTG Pactual XLSX bank statement.
     */
    @PostMapping(value = "/xlsx", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImportDtos.ImportResult> importXlsx(
            @RequestPart("file") MultipartFile file,
            @AuthenticationPrincipal User currentUser
    ) {
        return ResponseEntity.ok(service.importXlsx(file, currentUser));
    }

    /**
     * POST /api/imports/ofx
     * Imports an OFX bank statement (Nubank, Itaú, Bradesco, etc.).
     */
    @PostMapping(value = "/ofx", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImportDtos.ImportResult> importOfx(
            @RequestPart("file") MultipartFile file,
            @AuthenticationPrincipal User currentUser
    ) {
        return ResponseEntity.ok(service.importOfx(file, currentUser));
    }
}
