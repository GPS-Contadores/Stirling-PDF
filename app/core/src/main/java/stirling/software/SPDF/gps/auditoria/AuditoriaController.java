package stirling.software.SPDF.gps.auditoria;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Consulta da trilha de auditoria.
 *
 * <p>Acesso só para os e-mails de {@code gps.auditoria.leitores} (variável {@code
 * GPS_AUDITORIA_LEITORES}, separados por vírgula). Lista vazia fecha para todos. Quando os papéis
 * da #19 existirem, a lista dá lugar aos papéis admin e auditor.
 */
@RestController
@RequestMapping("/api/v1/gps/auditoria")
@Tag(name = "GPS", description = "Trilha de auditoria da assinatura digital")
public class AuditoriaController {

    private static final int LIMITE_MAXIMO = 5000;

    private final TrilhaDeAuditoria trilha;
    private final Set<String> leitores;

    public AuditoriaController(
            TrilhaDeAuditoria trilha, @Value("${gps.auditoria.leitores:}") String leitores) {
        this.trilha = trilha;
        this.leitores =
                Arrays.stream(leitores.split(","))
                        .map(e -> e.trim().toLowerCase(Locale.ROOT))
                        .filter(e -> !e.isEmpty())
                        .collect(Collectors.toUnmodifiableSet());
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Consultar a trilha de auditoria das assinaturas",
            description =
                    "Eventos do mais recente para o mais antigo, com a verificação da cadeia de"
                            + " hash. Só para leitores configurados em GPS_AUDITORIA_LEITORES.")
    public ResponseEntity<String> consultar(
            @RequestParam(required = false) String usuario,
            @RequestParam(required = false) String certificado,
            @RequestParam(required = false) String documento,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate ate,
            @RequestParam(defaultValue = "500") int limite)
            throws IOException {
        IdentidadeDoProxy quem = IdentidadeDoProxy.doMdc();
        if (!quem.presente() || !leitores.contains(quem.email().toLowerCase(Locale.ROOT))) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Consulta da trilha restrita aos auditores.");
        }
        List<EventoDeAuditoria> eventos =
                trilha.consultar(
                        new FiltroDaConsulta(usuario, certificado, documento, de, ate),
                        Math.max(1, Math.min(limite, LIMITE_MAXIMO)));
        // Com String no corpo, sem charset explícito o Spring escreve em ISO-8859-1 e o
        // "ç" do motivo chega quebrado.
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8))
                .body(trilha.paraJson(new Resposta(trilha.verificar(), eventos.size(), eventos)));
    }

    /** Mesmo formato (snake_case) das linhas do arquivo. */
    record Resposta(
            TrilhaDeAuditoria.Integridade integridade,
            int total,
            List<EventoDeAuditoria> eventos) {}
}
