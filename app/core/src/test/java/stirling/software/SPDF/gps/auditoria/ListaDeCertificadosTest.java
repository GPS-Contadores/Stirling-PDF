package stirling.software.SPDF.gps.auditoria;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.SPDF.gps.auditoria.PapeisDoUsuario.Papel;

class ListaDeCertificadosTest {

    @TempDir Path dir;

    private static final EventoDeAuditoria.Certificado CNPJ_GPS =
            new EventoDeAuditoria.Certificado(
                    "GPS CONTADORES LTDA:11222333000181",
                    "11222333000181",
                    "1092",
                    "AC Teste",
                    null,
                    "AB12CD",
                    null);

    private static final String LISTA =
            """
            padrao: negar
            certificados:
              - descricao: GPS matriz
                cpf_cnpj: "11.222.333/0001-81"
                permitidos: [fulano@gestao.com.br, "papel:assinante"]
              - descricao: e-CPF do Beltrano
                sha256: ffee00
                permitidos: [beltrano@gestao.com.br]
            """;

    private ListaDeCertificados com(String yaml) throws Exception {
        Path arquivo = dir.resolve("certificados.yml");
        Files.writeString(arquivo, yaml);
        return new ListaDeCertificados(arquivo, ListaDeCertificados.NEGAR);
    }

    private static IdentidadeDoProxy quem(String email) {
        return new IdentidadeDoProxy(email, null, null, List.of(), null);
    }

    private static final Set<Papel> SO_USUARIO = EnumSet.of(Papel.USUARIO);

    @Test
    void permitidoPorEmailSemDiferencaDeMaiusculas() throws Exception {
        assertThat(
                        com(LISTA)
                                .decidir(CNPJ_GPS, quem("FULANO@gestao.com.br"), SO_USUARIO)
                                .permitido())
                .isTrue();
    }

    @Test
    void permitidoPorPapel() throws Exception {
        assertThat(
                        com(LISTA)
                                .decidir(
                                        CNPJ_GPS,
                                        quem("chefe@x"),
                                        EnumSet.of(Papel.USUARIO, Papel.ASSINANTE))
                                .permitido())
                .isTrue();
    }

    @Test
    void naListaMasSemPermissaoENegadoComADescricao() throws Exception {
        ListaDeCertificados.Decisao decisao =
                com(LISTA).decidir(CNPJ_GPS, quem("beltrano@gestao.com.br"), SO_USUARIO);

        assertThat(decisao.permitido()).isFalse();
        assertThat(decisao.motivo()).isEqualTo("sem permissão para o certificado [GPS matriz]");
    }

    @Test
    void foraDaListaSegueOPadrao() throws Exception {
        EventoDeAuditoria.Certificado outro =
                new EventoDeAuditoria.Certificado(
                        null, "99999999000199", null, null, null, "0000", null);

        assertThat(com(LISTA).decidir(outro, quem("fulano@gestao.com.br"), SO_USUARIO).motivo())
                .isEqualTo("certificado fora da lista (padrão negar)");
        assertThat(
                        com(LISTA.replace("padrao: negar", "padrao: permitir"))
                                .decidir(outro, quem("fulano@gestao.com.br"), SO_USUARIO)
                                .permitido())
                .isTrue();
    }

    @Test
    void entradaComDuasChavesExigeAsDuas() throws Exception {
        ListaDeCertificados lista =
                com(
                        """
                        certificados:
                          - cpf_cnpj: "11222333000181"
                            sha256: "outro-hash"
                            permitidos: [fulano@gestao.com.br]
                        """);

        assertThat(lista.decidir(CNPJ_GPS, quem("fulano@gestao.com.br"), SO_USUARIO).motivo())
                .isEqualTo("certificado fora da lista (padrão negar)");
    }

    @Test
    void certificadoNaoIdentificadoSegueOPadrao() throws Exception {
        assertThat(com(LISTA).decidir(null, quem("fulano@gestao.com.br"), SO_USUARIO).motivo())
                .isEqualTo("certificado não identificado (padrão negar)");
    }

    @Test
    void semArquivoValeOPadraoDaVariavel() {
        Path ausente = dir.resolve("nao-existe.yml");

        assertThat(
                        new ListaDeCertificados(ausente, ListaDeCertificados.NEGAR)
                                .decidir(CNPJ_GPS, quem("fulano@gestao.com.br"), SO_USUARIO)
                                .permitido())
                .isFalse();
        assertThat(
                        new ListaDeCertificados(ausente, ListaDeCertificados.PERMITIR)
                                .decidir(CNPJ_GPS, quem("fulano@gestao.com.br"), SO_USUARIO)
                                .permitido())
                .isTrue();
    }

    @Test
    void listaQuebradaNegaTudoMesmoComPadraoPermitir() throws Exception {
        for (String quebrada :
                List.of(
                        "padrao: permitir\ncertificados: [\n",
                        "padrao: talvez\n",
                        "padrao: permitir\ncertificados:\n  - descricao: sem chave\n",
                        "padrao: permitir\ncertificados:\n  - cpf_cnpj: 1234567000189\n")) {
            ListaDeCertificados.Decisao decisao =
                    com(quebrada).decidir(CNPJ_GPS, quem("fulano@gestao.com.br"), SO_USUARIO);
            assertThat(decisao.permitido()).as(quebrada).isFalse();
            assertThat(decisao.motivo()).as(quebrada).startsWith("lista de certificados inválida");
        }
    }

    /**
     * O snakeyaml-engine usa o schema JSON: {@code 01234567000189} sem aspas continua texto, com o
     * zero; {@code 1234567000189} vira inteiro e é recusado (em {@link
     * #listaQuebradaNegaTudoMesmoComPadraoPermitir}). Exigir texto não depende do schema.
     */
    @Test
    void cnpjComZeroNaFrenteSemAspasContinuaTexto() throws Exception {
        EventoDeAuditoria.Certificado comZero =
                new EventoDeAuditoria.Certificado(
                        null, "01234567000189", null, null, null, "00", null);

        assertThat(
                        com("certificados:\n  - cpf_cnpj: 01234567000189\n"
                                        + "    permitidos: [fulano@gestao.com.br]\n")
                                .decidir(comZero, quem("fulano@gestao.com.br"), SO_USUARIO)
                                .permitido())
                .isTrue();
    }

    @Test
    void cnpjQueOYamlLeComoNumeroPedeAspas() throws Exception {
        assertThat(
                        com("certificados:\n  - cpf_cnpj: 11222333000181\n")
                                .decidir(CNPJ_GPS, quem("fulano@gestao.com.br"), SO_USUARIO)
                                .motivo())
                .contains("tem de estar entre aspas");
    }

    @Test
    void releOArquivoQuandoMudaSemReiniciar() throws Exception {
        ListaDeCertificados lista = com(LISTA);
        assertThat(lista.decidir(CNPJ_GPS, quem("novo@gestao.com.br"), SO_USUARIO).permitido())
                .isFalse();

        Path arquivo = dir.resolve("certificados.yml");
        Files.writeString(arquivo, LISTA.replace("fulano@gestao.com.br", "novo@gestao.com.br"));
        Files.setLastModifiedTime(arquivo, FileTime.from(Instant.now().plusSeconds(5)));

        assertThat(lista.decidir(CNPJ_GPS, quem("novo@gestao.com.br"), SO_USUARIO).permitido())
                .isTrue();
    }
}
