package br.dev.duofinance.imports.controller;

import br.dev.duofinance.imports.dto.PreviewResponse;
import br.dev.duofinance.imports.service.ImportContextLoader;
import br.dev.duofinance.imports.service.ImportPlan;
import br.dev.duofinance.imports.service.ImportPreviewService;
import br.dev.duofinance.shared.error.ApiException;
import br.dev.duofinance.shared.security.CurrentUser;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v1/imports")
public class ImportController {

    private static final TypeReference<Map<String, UUID>> MAPPING_TYPE = new TypeReference<>() {};

    private final ImportPreviewService previews;
    private final JsonMapper json;

    public ImportController(ImportPreviewService previews, JsonMapper json) {
        this.previews = previews;
        this.json = json;
    }

    @PostMapping(path = "/revolut/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PreviewResponse preview(
            CurrentUser current,
            @RequestPart("file") MultipartFile file,
            @RequestPart("bankAccountId") String bankAccountId,
            @RequestPart(name = "productMapping", required = false) String productMapping)
            throws IOException {
        UUID bank = parseUuid(bankAccountId);
        Map<String, UUID> mapping = parseMapping(productMapping);
        ImportPlan plan = previews.preview(current.accountId(), current.userId(), file.getBytes(), bank, mapping);
        return PreviewResponse.of(plan, ImportContextLoader.effectiveMapping(bank, mapping));
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.invalidField("bankAccountId", "Invalid");
        }
    }

    private Map<String, UUID> parseMapping(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return json.readValue(raw, MAPPING_TYPE);
        } catch (JacksonException e) {
            throw ApiException.invalidField("productMapping", "Invalid");
        }
    }
}
