package stirling.software.SPDF.gps.auditoria;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import stirling.software.SPDF.gps.auditoria.PapeisDoUsuario.Papel;

class PapeisDoUsuarioTest {

    private final PapeisDoUsuario papeis =
            new PapeisDoUsuario(
                    "Documentos.Admin",
                    "Documentos.Auditor",
                    "Documentos.Assinante",
                    " leitor@gestao.com.br , outro@gestao.com.br");

    private static IdentidadeDoProxy quem(String email, String... grupos) {
        return new IdentidadeDoProxy(email, null, null, List.of(grupos), null);
    }

    @Test
    void semIdentidadeNaoTemPapel() {
        assertThat(papeis.de(quem(null))).isEmpty();
        assertThat(papeis.de(null)).isEmpty();
    }

    @Test
    void todoUsuarioIdentificadoEUsuario() {
        assertThat(papeis.de(quem("fulano@gestao.com.br"))).containsExactly(Papel.USUARIO);
    }

    @Test
    void appRolesViramPapeisSemDiferencaDeMaiusculas() {
        assertThat(papeis.de(quem("a@x", "documentos.ASSINANTE")))
                .containsExactlyInAnyOrder(Papel.USUARIO, Papel.ASSINANTE);
        assertThat(papeis.de(quem("a@x", "Documentos.Auditor")))
                .containsExactlyInAnyOrder(Papel.USUARIO, Papel.AUDITOR);
    }

    @Test
    void adminIncluiAuditorEAssinante() {
        assertThat(papeis.de(quem("a@x", "Documentos.Admin")))
                .containsExactlyInAnyOrder(
                        Papel.USUARIO, Papel.ADMIN, Papel.AUDITOR, Papel.ASSINANTE);
    }

    @Test
    void emailEmLeitoresContaComoAuditorAteOsAppRolesExistirem() {
        assertThat(papeis.de(quem("Leitor@Gestao.com.br")))
                .containsExactlyInAnyOrder(Papel.USUARIO, Papel.AUDITOR);
    }

    @Test
    void grupoQueNaoEAppRoleNaoDaPapel() {
        assertThat(papeis.de(quem("a@x", "Todos", "Documentos"))).containsExactly(Papel.USUARIO);
    }
}
