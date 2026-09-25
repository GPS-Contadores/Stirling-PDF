package stirling.software.SPDF.gps.auditoria;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrilhaDeAuditoriaTest {

    @TempDir Path dir;

    private static Clock em(String instante) {
        return Clock.fixed(Instant.parse(instante), ZoneOffset.UTC);
    }

    private static EventoDeAuditoria evento(String email, String documento) {
        return new EventoDeAuditoria(
                null,
                null,
                new EventoDeAuditoria.Usuario(email, "id-" + email, email, List.of(), "10.0.0.1"),
                "cert-sign/PFX",
                new EventoDeAuditoria.Certificado(
                        "GPS CONTADORES LTDA",
                        "11222333000181",
                        "1092",
                        "AC Teste",
                        null,
                        "ab",
                        "cd"),
                new EventoDeAuditoria.Documento(documento, "aa", "bb", 10L),
                EventoDeAuditoria.SUCESSO,
                null,
                null);
    }

    @Test
    void encadeiaAsLinhasAtravessandoOMes() throws Exception {
        new TrilhaDeAuditoria(dir, em("2026-09-30T23:59:00Z"))
                .registrar(evento("a@gestao.com.br", "a.pdf"));
        TrilhaDeAuditoria outubro = new TrilhaDeAuditoria(dir, em("2026-10-01T00:01:00Z"));
        outubro.registrar(evento("b@gestao.com.br", "b.pdf"));

        Path setembro = dir.resolve("assinaturas-2026-09.jsonl");
        Path proximo = dir.resolve("assinaturas-2026-10.jsonl");
        String primeira = Files.readAllLines(setembro).get(0);
        assertThat(primeira).contains("\"hash_anterior\":\"" + "0".repeat(64) + "\"");
        assertThat(Files.readAllLines(proximo).get(0))
                .contains("\"hash_anterior\":\"" + TrilhaDeAuditoria.sha256(primeira) + "\"");
        assertThat(outubro.verificar().integra()).isTrue();
        assertThat(outubro.verificar().eventos()).isEqualTo(2);
    }

    @Test
    void naoGravaSenhaNemConteudoSoOQueFoiPassado() throws Exception {
        TrilhaDeAuditoria trilha = new TrilhaDeAuditoria(dir, em("2026-09-24T12:00:00Z"));
        EventoDeAuditoria gravado = trilha.registrar(evento("a@gestao.com.br", "a.pdf"));

        String linha = Files.readString(dir.resolve("assinaturas-2026-09.jsonl"));
        assertThat(gravado.id()).isNotBlank();
        assertThat(gravado.quando()).isEqualTo("2026-09-24T12:00:00Z");
        assertThat(linha).contains("\"cpf_cnpj\":\"11222333000181\"", "\"sha256_antes\":\"aa\"");
        assertThat(linha).doesNotContain("senha", "password");
    }

    @Test
    void editarUmaLinhaQuebraACadeiaNaLinhaSeguinte() throws Exception {
        TrilhaDeAuditoria trilha = new TrilhaDeAuditoria(dir, em("2026-09-24T12:00:00Z"));
        for (int i = 0; i < 3; i++) {
            trilha.registrar(evento("a@gestao.com.br", "doc" + i + ".pdf"));
        }
        Path arquivo = dir.resolve("assinaturas-2026-09.jsonl");
        List<String> linhas = new ArrayList<>(Files.readAllLines(arquivo));
        linhas.set(1, linhas.get(1).replace("a@gestao.com.br", "outro@gestao.com.br"));
        Files.write(arquivo, linhas, StandardCharsets.UTF_8);

        TrilhaDeAuditoria.Integridade integridade = trilha.verificar();
        assertThat(integridade.integra()).isFalse();
        assertThat(integridade.arquivo()).isEqualTo("assinaturas-2026-09.jsonl");
        assertThat(integridade.linha()).isEqualTo(3);
    }

    @Test
    void apagarUmaLinhaQuebraACadeia() throws Exception {
        TrilhaDeAuditoria trilha = new TrilhaDeAuditoria(dir, em("2026-09-24T12:00:00Z"));
        for (int i = 0; i < 3; i++) {
            trilha.registrar(evento("a@gestao.com.br", "doc" + i + ".pdf"));
        }
        Path arquivo = dir.resolve("assinaturas-2026-09.jsonl");
        List<String> linhas = new ArrayList<>(Files.readAllLines(arquivo));
        linhas.remove(0);
        Files.write(arquivo, linhas, StandardCharsets.UTF_8);

        assertThat(trilha.verificar().integra()).isFalse();
        assertThat(trilha.verificar().linha()).isEqualTo(1);
    }

    @Test
    void aposReiniciarContinuaACadeiaDoDisco() throws Exception {
        new TrilhaDeAuditoria(dir, em("2026-09-24T12:00:00Z"))
                .registrar(evento("a@gestao.com.br", "a.pdf"));
        TrilhaDeAuditoria reiniciada = new TrilhaDeAuditoria(dir, em("2026-09-24T13:00:00Z"));
        reiniciada.registrar(evento("b@gestao.com.br", "b.pdf"));

        assertThat(reiniciada.verificar().integra()).isTrue();
        assertThat(reiniciada.verificar().eventos()).isEqualTo(2);
    }

    @Test
    void consultaDoMaisRecenteParaOMaisAntigoComFiltroELimite() throws Exception {
        new TrilhaDeAuditoria(dir, em("2026-08-10T12:00:00Z"))
                .registrar(evento("a@gestao.com.br", "agosto.pdf"));
        TrilhaDeAuditoria trilha = new TrilhaDeAuditoria(dir, em("2026-09-10T12:00:00Z"));
        trilha.registrar(evento("b@gestao.com.br", "setembro-b.pdf"));
        trilha.registrar(evento("a@gestao.com.br", "setembro-a.pdf"));

        assertThat(trilha.consultar(e -> true, 10))
                .extracting(e -> e.documento().nome())
                .containsExactly("setembro-a.pdf", "setembro-b.pdf", "agosto.pdf");
        assertThat(trilha.consultar(e -> true, 1)).hasSize(1);
        assertThat(trilha.consultar(new FiltroDaConsulta("A@GESTAO", null, null, null, null), 10))
                .extracting(e -> e.documento().nome())
                .containsExactly("setembro-a.pdf", "agosto.pdf");
        assertThat(
                        trilha.consultar(
                                new FiltroDaConsulta(
                                        null,
                                        "11.222.333/0001-81",
                                        null,
                                        LocalDate.of(2026, 9, 1),
                                        LocalDate.of(2026, 9, 30)),
                                10))
                .hasSize(2);
    }

    @Test
    void semArquivoATrilhaEstaIntegraEVazia() throws Exception {
        TrilhaDeAuditoria trilha = new TrilhaDeAuditoria(dir.resolve("nada"), Clock.systemUTC());
        assertThat(trilha.verificar().integra()).isTrue();
        assertThat(trilha.consultar(e -> true, 10)).isEmpty();
    }
}
