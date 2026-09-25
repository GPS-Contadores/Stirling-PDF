package stirling.software.SPDF.gps.auditoria;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
        return passarPeloFiltro(FiltroIdentidadeDoProxy.QUALQUER_ORIGEM, request, null);
    }

    /**
     * No teste o par da conexão é o {@code remoteAddr} do mock; no Jetty é o socket ({@link
     * FiltroIdentidadeDoProxy#parNoJetty}).
     */
    private static IdentidadeDoProxy passarPeloFiltro(
            String origens, MockHttpServletRequest request, AtomicReference<String> recusada)
            throws Exception {
        AtomicReference<IdentidadeDoProxy> vista = new AtomicReference<>();
        new FiltroIdentidadeDoProxy(origens, r -> endereco(r.getRemoteAddr()))
                .doFilter(
                        request,
                        new MockHttpServletResponse(),
                        (req, res) -> {
                            vista.set(IdentidadeDoProxy.doMdc());
                            if (recusada != null) {
                                recusada.set(IdentidadeDoProxy.origemRecusada());
                            }
                        });
        return vista.get();
    }

    private static InetAddress endereco(String ip) {
        try {
            return InetAddress.getByName(ip);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private static MockHttpServletRequest comIdentidade(String par) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/x");
        request.setRemoteAddr(par);
        request.addHeader("X-Forwarded-Email", "auditor@gestao.com.br");
        request.addHeader("X-Forwarded-Groups", "Documentos.Auditor");
        request.addHeader("X-Forwarded-For", "10.9.9.9");
        return request;
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

    @Test
    void identidadeDeOutraOrigemEIgnoradaEOIpEODoSocket() throws Exception {
        AtomicReference<String> recusada = new AtomicReference<>();

        IdentidadeDoProxy identidade =
                passarPeloFiltro("127.0.0.1", comIdentidade("fd12::6"), recusada);

        assertThat(identidade.presente()).isFalse();
        assertThat(identidade.grupos()).isEmpty();
        assertThat(identidade.ip()).isEqualTo(endereco("fd12::6").getHostAddress());
        assertThat(recusada.get()).isEqualTo(identidade.ip());
    }

    @Test
    void proxyConfiavelPeloNomeQueResolveParaOPar() throws Exception {
        IdentidadeDoProxy identidade =
                passarPeloFiltro(" outro.invalid , localhost", comIdentidade("127.0.0.1"), null);

        assertThat(identidade.email()).isEqualTo("auditor@gestao.com.br");
        assertThat(identidade.grupos()).containsExactly("Documentos.Auditor");
        assertThat(identidade.ip()).isEqualTo("10.9.9.9");
    }

    @Test
    void semProxyConfiguradoNinguemTemIdentidade() throws Exception {
        assertThat(passarPeloFiltro("", comIdentidade("127.0.0.1"), null).presente()).isFalse();
    }

    @Test
    void semParConhecidoNaoHaIdentidade() throws Exception {
        AtomicReference<IdentidadeDoProxy> vista = new AtomicReference<>();
        new FiltroIdentidadeDoProxy("*", r -> null)
                .doFilter(
                        comIdentidade("127.0.0.1"),
                        new MockHttpServletResponse(),
                        (req, res) -> vista.set(IdentidadeDoProxy.doMdc()));

        assertThat(vista.get().presente()).isFalse();
    }

    @Test
    void soResolveODnsDoProxyQuandoARequisicaoTrazIdentidade() throws Exception {
        AtomicInteger consultas = new AtomicInteger();
        FiltroIdentidadeDoProxy filtro =
                new FiltroIdentidadeDoProxy(
                        "auth-proxy.railway.internal",
                        r -> endereco(r.getRemoteAddr()),
                        nome -> {
                            consultas.incrementAndGet();
                            return new InetAddress[] {endereco("127.0.0.1")};
                        });
        MockHttpServletRequest css = new MockHttpServletRequest("GET", "/assets/app.css");
        css.setRemoteAddr("127.0.0.1");
        AtomicReference<IdentidadeDoProxy> vista = new AtomicReference<>();

        filtro.doFilter(css, new MockHttpServletResponse(), (req, res) -> {});
        assertThat(consultas).hasValue(0);

        filtro.doFilter(
                comIdentidade("127.0.0.1"),
                new MockHttpServletResponse(),
                (req, res) -> vista.set(IdentidadeDoProxy.doMdc()));
        assertThat(consultas).hasValue(1);
        assertThat(vista.get().email()).isEqualTo("auditor@gestao.com.br");
    }

    @Test
    void avisoRepetidoSaiUmaVezPorIntervaloContandoOsSuprimidos() {
        AtomicLong agora = new AtomicLong();
        FiltroIdentidadeDoProxy.AvisoComIntervalo avisos =
                new FiltroIdentidadeDoProxy.AvisoComIntervalo(Duration.ofMinutes(1), agora::get);

        assertThat(avisos.avisar("dns:auth-proxy")).isZero();
        assertThat(avisos.avisar("dns:auth-proxy")).isEqualTo(-1);
        assertThat(avisos.avisar("dns:auth-proxy")).isEqualTo(-1);
        assertThat(avisos.avisar("recusada:fd12::6")).isZero();

        agora.addAndGet(Duration.ofMinutes(1).toNanos());
        assertThat(avisos.avisar("dns:auth-proxy")).isEqualTo(2);
        assertThat(avisos.avisar("dns:auth-proxy")).isEqualTo(-1);
    }
}
