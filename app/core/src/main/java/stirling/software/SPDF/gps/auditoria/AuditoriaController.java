package stirling.software.SPDF.gps.auditoria;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

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
 * <p>Acesso só para os papéis {@code auditor} e {@code admin} ({@link PapeisDoUsuario}): App Role
 * do Entra repassada pelo proxy ou, enquanto os App Roles não existem, e-mail em {@code
 * gps.auditoria.leitores} ({@code GPS_AUDITORIA_LEITORES}). Sem nenhum dos dois, fecha para todos.
 */
@RestController
@RequestMapping("/api/v1/gps/auditoria")
@Tag(name = "GPS", description = "Trilha de auditoria da assinatura digital")
public class AuditoriaController {

    private static final int LIMITE_MAXIMO = 5000;

    private final TrilhaDeAuditoria trilha;
    private final PapeisDoUsuario papeis;

    public AuditoriaController(TrilhaDeAuditoria trilha, PapeisDoUsuario papeis) {
        this.trilha = trilha;
        this.papeis = papeis;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Consultar a trilha de auditoria das assinaturas",
            description =
                    "Eventos do mais recente para o mais antigo, com a verificação da cadeia de"
                            + " hash. Só para os papéis auditor e admin.")
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
        Set<PapeisDoUsuario.Papel> deQuem = papeis.de(IdentidadeDoProxy.doMdc());
        if (!deQuem.contains(PapeisDoUsuario.Papel.AUDITOR)) {
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
