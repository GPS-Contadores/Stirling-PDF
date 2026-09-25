package stirling.software.SPDF.gps.auditoria;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class FiltroIdentidadeDoProxyTest {

    @AfterEach
    void limpar() {
        MDC.clear();
    }

    private static IdentidadeDoProxy passarPeloFiltro(MockHttpServletRequest request)
            throws Exception {
        AtomicReference<IdentidadeDoProxy> vista = new AtomicReference<>();
        new FiltroIdentidadeDoProxy()
                .doFilter(
                        request,
                        new MockHttpServletResponse(),
                        (req, res) -> vista.set(IdentidadeDoProxy.doMdc()));
        return vista.get();
    }

    @Test
    void levaOsHeadersDoProxyParaOMdcDuranteARequisicao() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/x");
        request.addHeader("X-Forwarded-Email", "fulano@gestao.com.br");
        request.addHeader("X-Forwarded-User", "c0ffee-id");
        request.addHeader("X-Forwarded-Preferred-Username", "fulano@gestao.com.br");
        request.addHeader("X-Forwarded-Groups", "Documentos.Assinante,Documentos.Auditor");
        request.addHeader("X-Forwarded-Groups", "Outro");
        request.addHeader("X-Forwarded-For", "200.1.2.3, 10.0.0.2");

        IdentidadeDoProxy identidade = passarPeloFiltro(request);

        assertThat(identidade.presente()).isTrue();
        assertThat(identidade.email()).isEqualTo("fulano@gestao.com.br");
        assertThat(identidade.usuario()).isEqualTo("c0ffee-id");
        assertThat(identidade.grupos())
                .containsExactly("Documentos.Assinante", "Documentos.Auditor", "Outro");
        assertThat(identidade.ip()).isEqualTo("200.1.2.3, 10.0.0.2");
        assertThat(MDC.get(IdentidadeDoProxy.MDC_EMAIL)).isNull();
    }

    @Test
    void semHeaderNaoHaIdentidadeEOIpEODaConexao() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/x");
        request.setRemoteAddr("127.0.0.1");

        IdentidadeDoProxy identidade = passarPeloFiltro(request);

        assertThat(identidade.presente()).isFalse();
        assertThat(identidade.grupos()).isEmpty();
        assertThat(identidade.ip()).isEqualTo("127.0.0.1");
    }

    @Test
    void naoHerdaUsuarioQueSobrouNaThread() throws Exception {
        MDC.put(IdentidadeDoProxy.MDC_EMAIL, "sobra@gestao.com.br");

        IdentidadeDoProxy identidade =
                passarPeloFiltro(new MockHttpServletRequest("POST", "/api/v1/x"));

        assertThat(identidade.presente()).isFalse();
    }
}
