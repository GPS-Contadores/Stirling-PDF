package stirling.software.SPDF.gps.auditoria;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static stirling.software.SPDF.gps.auditoria.AuditoriaDaAssinaturaAspectTest.CNPJ;
import static stirling.software.SPDF.gps.auditoria.AuditoriaDaAssinaturaAspectTest.SENHA;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.aspectj.annotation.ReflectiveAspectJAdvisorFactory;
import org.springframework.aop.aspectj.annotation.SingletonMetadataAwareAspectInstanceFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.interceptor.ExposeInvocationInterceptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import stirling.software.SPDF.service.PdfSigningServiceImpl;
import stirling.software.common.service.CustomPDFDocumentFactory;
import stirling.software.common.service.PdfSigningService;

/**
 * O aspecto em volta do {@link PdfSigningServiceImpl} real, como a finalização de sessão o chama.
 */
class AuditoriaDaSessaoDeAssinaturaAspectTest {

    private static final String VALIDADOR =
            "stirling.software.proprietary.workflow.service.CertificateSubmissionValidator";
    private static final String URL_DA_FINALIZACAO =
            "/api/v1/security/cert-sign/sessions/sessao-42/finalize";

    @TempDir Path dir;

    private TrilhaDeAuditoria trilha;
    private CustomPDFDocumentFactory fabrica;
    private byte[] pdf;
    private byte[] pfx;
    private KeyStore keystore;

    @BeforeEach
    void preparar() throws Exception {
        trilha = new TrilhaDeAuditoria(dir.resolve("audit"), Clock.systemUTC());
        fabrica = mock(CustomPDFDocumentFactory.class);
        lenient()
                .when(fabrica.load(any(MultipartFile.class)))
                .thenAnswer(inv -> Loader.loadPDF(inv.<MultipartFile>getArgument(0).getBytes()));
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            ByteArrayOutputStream saida = new ByteArrayOutputStream();
            doc.save(saida);
            pdf = saida.toByteArray();
        }
        pfx = AuditoriaDaAssinaturaAspectTest.pfxIcpBrasil();
        keystore = KeyStore.getInstance("PKCS12");
        keystore.load(new ByteArrayInputStream(pfx), SENHA.toCharArray());
    }

    @AfterEach
    void limparRequisicao() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void finalizacaoComSucessoVaiParaATrilhaComOUsuarioDoLogin() throws Exception {
        requisicao("fulano@gestao.com.br");

        byte[] assinado = assinar(servico(new PdfSigningServiceImpl(fabrica)), SENHA);

        EventoDeAuditoria evento = unicoEvento();
        assertThat(evento.resultado()).isEqualTo(EventoDeAuditoria.SUCESSO);
        assertThat(evento.usuario().email()).isEqualTo("fulano@gestao.com.br");
        assertThat(evento.usuario().nomeDeUsuario()).isEqualTo("fulano@gestao.com.br");
        assertThat(evento.ferramenta()).isEqualTo("cert-sign/sessao/PKCS12");
        assertThat(evento.certificado().cpfCnpj()).isEqualTo(CNPJ);
        assertThat(evento.certificado().serie()).isEqualTo("1092");
        assertThat(evento.documento().nome()).isEqualTo("sessão sessao-42");
        assertThat(evento.documento().sha256Antes()).isEqualTo(sha256(pdf));
        assertThat(evento.documento().sha256Depois()).isEqualTo(sha256(assinado));
        assertThat(evento.documento().bytesDepois()).isEqualTo(assinado.length);
    }

    @Test
    void usuarioSemEmailFicaSoComoNomeDeUsuario() throws Exception {
        requisicao("fulano");

        assinar(servico(new PdfSigningServiceImpl(fabrica)), SENHA);

        EventoDeAuditoria evento = unicoEvento();
        assertThat(evento.usuario().email()).isNull();
        assertThat(evento.usuario().nomeDeUsuario()).isEqualTo("fulano");
    }

    @Test
    void semUsuarioLogadoAAssinaturaENegadaSemAssinar() throws Exception {
        requisicao(null);
        CustomPDFDocumentFactory nuncaUsada = mock(CustomPDFDocumentFactory.class);

        assertThatThrownBy(() -> assinar(servico(new PdfSigningServiceImpl(nuncaUsada)), SENHA))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");

        verify(nuncaUsada, never()).load(any(MultipartFile.class));
        EventoDeAuditoria evento = unicoEvento();
        assertThat(evento.resultado()).isEqualTo(EventoDeAuditoria.NEGADO);
        assertThat(evento.motivo()).isEqualTo(AuditoriaDaSessaoDeAssinaturaAspect.SEM_LOGIN);
    }

    @Test
    void foraDeRequisicaoTambemENegada() throws Exception {
        assertThatThrownBy(() -> assinar(servico(new PdfSigningServiceImpl(fabrica)), SENHA))
                .isInstanceOf(ResponseStatusException.class);

        EventoDeAuditoria evento = unicoEvento();
        assertThat(evento.resultado()).isEqualTo(EventoDeAuditoria.NEGADO);
        assertThat(evento.documento().nome()).isNull();
    }

    @Test
    void senhaErradaViraErroSemASenhaNaTrilha() throws Exception {
        requisicao("fulano@gestao.com.br");

        assertThatThrownBy(() -> assinar(servico(new PdfSigningServiceImpl(fabrica)), "errada"))
                .isInstanceOf(Exception.class);

        EventoDeAuditoria evento = unicoEvento();
        assertThat(evento.resultado()).isEqualTo(EventoDeAuditoria.ERRO);
        assertThat(evento.motivo()).isNotBlank().doesNotContain("errada");
        assertThat(evento.certificado().cpfCnpj()).isNull();
    }

    @Test
    void pdfDevolvidoSemAssinaturaNovaViraErro() throws Exception {
        requisicao("fulano@gestao.com.br");
        PdfSigningService devolveAEntrada = (entrada, ks, senha, v, p, n, l, r, logo) -> entrada;

        byte[] devolvido = assinar(servico(devolveAEntrada), SENHA);

        assertThat(devolvido).isEqualTo(pdf);
        EventoDeAuditoria evento = unicoEvento();
        assertThat(evento.resultado()).isEqualTo(EventoDeAuditoria.ERRO);
        assertThat(evento.motivo())
                .isEqualTo(AuditoriaDaSessaoDeAssinaturaAspect.SEM_ASSINATURA_NOVA);
    }

    @Test
    void trilhaQueNaoGravaFalhaAAssinatura() throws Exception {
        requisicao("fulano@gestao.com.br");
        trilha = mock(TrilhaDeAuditoria.class);
        doThrow(new IOException("disco cheio")).when(trilha).registrar(any());

        assertThatThrownBy(() -> assinar(servico(new PdfSigningServiceImpl(fabrica)), SENHA))
                .isInstanceOf(IOException.class)
                .hasMessage("disco cheio");
    }

    /**
     * O teste de certificado do validador real não é assinatura entregue. Carregado por nome porque
     * o core compila sem o {@code proprietary}; o pointcut do aspecto também é texto, e este teste
     * é o que acusa se o upstream renomear a classe ou o método.
     */
    @Test
    void validacaoDoCertificadoNaoVaiParaATrilha() throws Exception {
        requisicao("fulano@gestao.com.br");
        Class<?> tipo;
        try {
            tipo = Class.forName(VALIDADOR);
        } catch (ClassNotFoundException e) {
            tipo = null;
        }
        assumeThat(tipo).as("proprietary fora do build").isNotNull();
        PdfSigningService servico = servico(new PdfSigningServiceImpl(fabrica));
        Object validador =
                comAuditoria(tipo.getConstructor(PdfSigningService.class).newInstance(servico));

        Object info;
        try {
            info =
                    tipo.getMethod(
                                    "validateAndExtractInfo",
                                    byte[].class,
                                    String.class,
                                    String.class)
                            .invoke(validador, pfx, "PFX", SENHA);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }

        assertThat(info).isNotNull();
        verify(fabrica).load(any(MultipartFile.class));
        assertThat(trilha.consultar(e -> true, 10)).isEmpty();

        // A marca some com a validação: a assinatura seguinte, na mesma thread, é registrada.
        assinar(servico, SENHA);
        assertThat(unicoEvento().resultado()).isEqualTo(EventoDeAuditoria.SUCESSO);
    }

    private byte[] assinar(PdfSigningService servico, String senha) throws Exception {
        return servico.signWithKeystore(
                pdf,
                keystore,
                senha.toCharArray(),
                false,
                null,
                "Fulano",
                "",
                "Document Signing",
                false);
    }

    private PdfSigningService servico(PdfSigningService alvo) {
        return (PdfSigningService) comAuditoria(alvo);
    }

    /**
     * A cadeia como o auto-proxy do Spring monta: um único Expose no início e o aspecto. O aspecto
     * é um só para o serviço e o validador, como o singleton do Spring: a marca da validação vive
     * nele.
     */
    private Object comAuditoria(Object alvo) {
        if (aspecto == null) {
            aspecto = new AuditoriaDaSessaoDeAssinaturaAspect(trilha, true);
        }
        ProxyFactory fabricaDeProxy = new ProxyFactory(alvo);
        fabricaDeProxy.setProxyTargetClass(!(alvo instanceof PdfSigningService));
        fabricaDeProxy.addAdvisor(ExposeInvocationInterceptor.ADVISOR);
        fabricaDeProxy.addAdvisors(
                new ReflectiveAspectJAdvisorFactory()
                        .getAdvisors(
                                new SingletonMetadataAwareAspectInstanceFactory(
                                        aspecto, "auditoriaDaSessao")));
        return fabricaDeProxy.getProxy();
    }

    private AuditoriaDaSessaoDeAssinaturaAspect aspecto;

    private void requisicao(String usuario) {
        MockHttpServletRequest requisicao = new MockHttpServletRequest("POST", URL_DA_FINALIZACAO);
        if (usuario != null) {
            requisicao.setUserPrincipal(() -> usuario);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(requisicao));
    }

    private EventoDeAuditoria unicoEvento() throws Exception {
        List<EventoDeAuditoria> eventos = trilha.consultar(e -> true, 10);
        assertThat(eventos).hasSize(1);
        assertThat(trilha.verificar().integra()).isTrue();
        return eventos.get(0);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of()
                .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
