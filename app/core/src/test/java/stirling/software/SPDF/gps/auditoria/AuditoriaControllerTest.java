package stirling.software.SPDF.gps.auditoria;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

class AuditoriaControllerTest {

    @TempDir Path dir;

    private TrilhaDeAuditoria trilha;

    @BeforeEach
    void preparar() throws Exception {
        trilha = new TrilhaDeAuditoria(dir, Clock.systemUTC());
        trilha.registrar(
                new EventoDeAuditoria(
                        null,
                        null,
                        new EventoDeAuditoria.Usuario(
                                "fulano@gestao.com.br", null, null, List.of(), null),
                        "cert-sign/PFX",
                        null,
                        new EventoDeAuditoria.Documento("contrato.pdf", "aa", "bb", 1L),
                        EventoDeAuditoria.SUCESSO,
                        null,
                        null));
    }

    @AfterEach
    void limpar() {
        MDC.clear();
    }

    private String consultar(String leitores) throws Exception {
        ResponseEntity<String> resposta =
                new AuditoriaController(trilha, leitores)
                        .consultar(null, null, null, null, null, 500);
        assertThat(resposta.getHeaders().getContentType().getCharset())
                .isEqualTo(StandardCharsets.UTF_8);
        return resposta.getBody();
    }

    @Test
    void leitorConfiguradoConsultaEmSnakeCaseComIntegridade() throws Exception {
        MDC.put(IdentidadeDoProxy.MDC_EMAIL, "Auditor@Gestao.com.br");

        String corpo = consultar(" auditor@gestao.com.br , outro@gestao.com.br");

        assertThat(corpo)
                .contains(
                        "\"integridade\":{\"integra\":true",
                        "\"total\":1",
                        "\"sha256_antes\":\"aa\"",
                        "\"email\":\"fulano@gestao.com.br\"");
    }

    @Test
    void quemNaoEstaNaListaRecebe403() {
        MDC.put(IdentidadeDoProxy.MDC_EMAIL, "fulano@gestao.com.br");

        assertThatThrownBy(() -> consultar("auditor@gestao.com.br"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
    }

    @Test
    void listaVaziaFechaParaTodos() {
        MDC.put(IdentidadeDoProxy.MDC_EMAIL, "auditor@gestao.com.br");

        assertThatThrownBy(() -> consultar("")).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void semIdentidadeRecebe403() {
        assertThatThrownBy(() -> consultar("auditor@gestao.com.br"))
                .isInstanceOf(ResponseStatusException.class);
    }
}
