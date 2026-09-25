package stirling.software.SPDF.gps.auditoria;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.DERTaggedObject;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;
import org.springframework.aop.aspectj.annotation.ReflectiveAspectJAdvisorFactory;
import org.springframework.aop.aspectj.annotation.SingletonMetadataAwareAspectInstanceFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.interceptor.ExposeInvocationInterceptor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;

import stirling.software.SPDF.controller.api.security.CertSignController;
import stirling.software.SPDF.model.api.security.SignPDFWithCertRequest;
import stirling.software.SPDF.service.HardwareKeyStoreService;
import stirling.software.common.service.CustomPDFDocumentFactory;
import stirling.software.common.util.TempFile;
import stirling.software.common.util.TempFileManager;

/**
 * O aspecto em volta do {@link CertSignController} real. O certificado é gerado aqui: os de {@code
 * test/resources/certs} venceram em 26/08/2026.
 */
class AuditoriaDaAssinaturaAspectTest {

    private static final String SENHA = "segredo-de-teste";
    private static final String CNPJ = "11222333000181";

    @TempDir Path dir;

    private TrilhaDeAuditoria trilha;
    private CustomPDFDocumentFactory fabrica;
    private byte[] pdf;
    private byte[] pfx;

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
        pfx = pfxIcpBrasil();
    }

    @AfterEach
    void limparMdc() {
        MDC.clear();
    }

    @Test
    void assinaturaComSucessoVaiParaATrilhaComCertificadoEHashes() throws Exception {
        MDC.put(IdentidadeDoProxy.MDC_EMAIL, "fulano@gestao.com.br");
        MDC.put(IdentidadeDoProxy.MDC_IP, "200.1.2.3, 10.0.0.2");

        ResponseEntity<Resource> resposta = controlador(true).signPDFWithCert(pedido(SENHA), null);

        Path assinado = ((FileSystemResource) resposta.getBody()).getFile().toPath();
        EventoDeAuditoria evento = unicoEvento();
        assertThat(evento.resultado()).isEqualTo(EventoDeAuditoria.SUCESSO);
        assertThat(evento.usuario().email()).isEqualTo("fulano@gestao.com.br");
        assertThat(evento.usuario().ip()).isEqualTo("200.1.2.3, 10.0.0.2");
        assertThat(evento.ferramenta()).isEqualTo("cert-sign/PFX");
        assertThat(evento.certificado().cpfCnpj()).isEqualTo(CNPJ);
        assertThat(evento.certificado().titular()).isEqualTo("GPS CONTADORES TESTE:99999999000199");
        assertThat(evento.certificado().serie()).isEqualTo("1092");
        assertThat(evento.certificado().arquivoSha256()).isEqualTo(sha256(pfx));
        assertThat(evento.documento().nome()).isEqualTo("contrato.pdf");
        assertThat(evento.documento().sha256Antes()).isEqualTo(sha256(pdf));
        assertThat(evento.documento().sha256Depois())
                .isEqualTo(sha256(Files.readAllBytes(assinado)));
        assertThat(evento.documento().bytesDepois()).isEqualTo(Files.size(assinado));
        assertThat(Files.readString(dir.resolve("audit").resolve(arquivoDoMes())))
                .doesNotContain(SENHA);
    }

    @Test
    void senhaErradaVaiParaATrilhaComoErroEOErroChegaAoCliente() throws Exception {
        MDC.put(IdentidadeDoProxy.MDC_EMAIL, "fulano@gestao.com.br");

        assertThatThrownBy(() -> controlador(true).signPDFWithCert(pedido("errada"), null))
                .isInstanceOf(Exception.class);

        EventoDeAuditoria evento = unicoEvento();
        assertThat(evento.resultado()).isEqualTo(EventoDeAuditoria.ERRO);
        assertThat(evento.motivo()).isNotBlank().doesNotContain("errada");
        assertThat(evento.certificado().arquivoSha256()).isEqualTo(sha256(pfx));
        assertThat(evento.certificado().cpfCnpj()).isNull();
    }

    @Test
    void semIdentidadeDoProxyAAssinaturaENegadaSemChegarAoControlador() throws Exception {
        CustomPDFDocumentFactory nuncaUsada = mock(CustomPDFDocumentFactory.class);

        assertThatThrownBy(() -> controlador(true, nuncaUsada).signPDFWithCert(pedido(SENHA), null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");

        verify(nuncaUsada, never()).load(any(MultipartFile.class));
        EventoDeAuditoria evento = unicoEvento();
        assertThat(evento.resultado()).isEqualTo(EventoDeAuditoria.NEGADO);
        assertThat(evento.motivo()).isEqualTo(AuditoriaDaAssinaturaAspect.SEM_IDENTIDADE);
        assertThat(evento.usuario().email()).isNull();
    }

    @Test
    void identidadeDeForaDoProxyENegadaComOEnderecoNaTrilha() throws Exception {
        MDC.put(IdentidadeDoProxy.MDC_ORIGEM_RECUSADA, "fd12:0:0:0:0:0:0:6");

        assertThatThrownBy(() -> controlador(true).signPDFWithCert(pedido(SENHA), null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");

        EventoDeAuditoria evento = unicoEvento();
        assertThat(evento.resultado()).isEqualTo(EventoDeAuditoria.NEGADO);
        assertThat(evento.motivo())
                .isEqualTo(
                        AuditoriaDaAssinaturaAspect.IDENTIDADE_FORA_DO_PROXY
                                + " (fd12:0:0:0:0:0:0:6)");
    }

    @Test
    void assinaturaQueJaEstavaNoPdfNaoContaComoNova() throws Exception {
        ResponseEntity<Resource> resposta = controlador(false).signPDFWithCert(pedido(SENHA), null);
        Path assinado = ((FileSystemResource) resposta.getBody()).getFile().toPath();

        assertThat(AssinaturaDoPdf.daUltimaAssinatura(assinado, pdf.length)).isPresent();
        // O mesmo PDF devolvido sem mudança: a assinatura é a de antes, não uma nova.
        assertThat(AssinaturaDoPdf.daUltimaAssinatura(assinado, Files.size(assinado))).isEmpty();
    }

    @Test
    void semExigirIdentidadeAssinaERegistraSemUsuario() throws Exception {
        controlador(false).signPDFWithCert(pedido(SENHA), null);

        EventoDeAuditoria evento = unicoEvento();
        assertThat(evento.resultado()).isEqualTo(EventoDeAuditoria.SUCESSO);
        assertThat(evento.usuario().email()).isNull();
    }

    @Test
    void falhaDoSignChegaAoClienteEViraErroNaTrilha() throws Exception {
        MDC.put(IdentidadeDoProxy.MDC_EMAIL, "fulano@gestao.com.br");
        // Desde o #34 o sign() repassa a falha em vez de responder 200 com PDF vazio.
        doThrow(new IOException("PDF corrompido")).when(fabrica).load(any(MultipartFile.class));

        assertThatThrownBy(() -> controlador(true).signPDFWithCert(pedido(SENHA), null))
                .isInstanceOf(IOException.class)
                .hasMessage("PDF corrompido");

        EventoDeAuditoria evento = unicoEvento();
        assertThat(evento.resultado()).isEqualTo(EventoDeAuditoria.ERRO);
        assertThat(evento.motivo()).isEqualTo("IOException: PDF corrompido");
        assertThat(evento.documento().sha256Antes()).isEqualTo(sha256(pdf));
        assertThat(evento.documento().sha256Depois()).isNull();
    }

    /**
     * A conferência pela saída continua como defesa: se algum caminho voltar a responder 200 sem
     * assinar, a trilha registra erro em vez de sucesso.
     */
    @Test
    void respostaSemAssinaturaNovaViraErroNaTrilha() throws Exception {
        MDC.put(IdentidadeDoProxy.MDC_EMAIL, "fulano@gestao.com.br");
        Path semAssinar = Files.write(dir.resolve("sem-assinatura.pdf"), pdf);

        ResponseEntity<Resource> resposta =
                comAuditoria(new DevolveSemAssinar(fabrica, semAssinar), true)
                        .signPDFWithCert(pedido(SENHA), null);

        assertThat(resposta.getStatusCode().is2xxSuccessful()).isTrue();
        EventoDeAuditoria evento = unicoEvento();
        assertThat(evento.resultado()).isEqualTo(EventoDeAuditoria.ERRO);
        assertThat(evento.motivo()).isEqualTo(AuditoriaDaAssinaturaAspect.SEM_ASSINATURA_NOVA);
        assertThat(evento.documento().sha256Depois()).isEqualTo(sha256(pdf));
    }

    /** Responde 200 com o PDF de entrada, sem assinar. */
    static class DevolveSemAssinar extends CertSignController {
        private final Path arquivo;

        DevolveSemAssinar(CustomPDFDocumentFactory fabrica, Path arquivo) {
            super(fabrica, null, mock(TempFileManager.class), mock(HardwareKeyStoreService.class));
            this.arquivo = arquivo;
        }

        @Override
        public ResponseEntity<Resource> signPDFWithCert(
                SignPDFWithCertRequest request, HttpServletRequest httpRequest) {
            return ResponseEntity.ok(new FileSystemResource(arquivo));
        }
    }

    private CertSignController controlador(boolean exigirIdentidade) {
        return controlador(exigirIdentidade, fabrica);
    }

    private CertSignController controlador(
            boolean exigirIdentidade, CustomPDFDocumentFactory fabricaDePdf) {
        TempFileManager temporarios = mock(TempFileManager.class);
        try {
            lenient()
                    .when(temporarios.createManagedTempFile(anyString()))
                    .thenAnswer(
                            inv -> {
                                File f =
                                        Files.createTempFile(dir, "assinado", inv.getArgument(0))
                                                .toFile();
                                TempFile tf = mock(TempFile.class);
                                lenient().when(tf.getFile()).thenReturn(f);
                                lenient().when(tf.getPath()).thenReturn(f.toPath());
                                return tf;
                            });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return comAuditoria(
                new CertSignController(
                        fabricaDePdf, null, temporarios, mock(HardwareKeyStoreService.class)),
                exigirIdentidade);
    }

    private CertSignController comAuditoria(CertSignController alvo, boolean exigirIdentidade) {
        // A cadeia como o auto-proxy do Spring monta: um único ExposeInvocationInterceptor no
        // início, o salto de thread por fora (AutoJobAspect, @Order(20)) e a auditoria por dentro.
        // O AspectJProxyFactory põe um Expose antes de cada aspecto e esconderia o defeito da
        // ThreadLocal.
        ReflectiveAspectJAdvisorFactory advisors = new ReflectiveAspectJAdvisorFactory();
        ProxyFactory fabricaDeProxy = new ProxyFactory(alvo);
        fabricaDeProxy.setProxyTargetClass(true);
        fabricaDeProxy.addAdvisor(ExposeInvocationInterceptor.ADVISOR);
        fabricaDeProxy.addAdvisors(
                advisors.getAdvisors(
                        new SingletonMetadataAwareAspectInstanceFactory(
                                new ProceedEmOutraThread(), "saltoDeThread")));
        fabricaDeProxy.addAdvisors(
                advisors.getAdvisors(
                        new SingletonMetadataAwareAspectInstanceFactory(
                                new AuditoriaDaAssinaturaAspect(trilha, exigirIdentidade),
                                "auditoria")));
        return (CertSignController) fabricaDeProxy.getProxy();
    }

    /**
     * Faz o {@code proceed(args)} numa thread virtual levando o MDC, como o {@code AutoJobAspect}
     * faz com todo {@code @AutoJobPostMapping}, inclusive síncrono.
     */
    @Aspect
    static class ProceedEmOutraThread {
        @Around(
                "execution(*"
                        + " stirling.software.SPDF.controller.api.security.CertSignController.signPDFWithCert(..))")
        public Object pular(ProceedingJoinPoint ponto) throws Throwable {
            Map<String, String> mdc = MDC.getCopyOfContextMap();
            CompletableFuture<Object> resultado = new CompletableFuture<>();
            Thread.ofVirtual()
                    .start(
                            () -> {
                                try {
                                    if (mdc != null) {
                                        MDC.setContextMap(mdc);
                                    }
                                    resultado.complete(ponto.proceed(ponto.getArgs()));
                                } catch (Throwable falha) {
                                    resultado.completeExceptionally(falha);
                                } finally {
                                    MDC.clear();
                                }
                            });
            try {
                return resultado.get();
            } catch (ExecutionException e) {
                throw e.getCause();
            }
        }
    }

    private SignPDFWithCertRequest pedido(String senha) {
        SignPDFWithCertRequest pedido = new SignPDFWithCertRequest();
        pedido.setFileInput(
                new MockMultipartFile("fileInput", "contrato.pdf", "application/pdf", pdf));
        pedido.setCertType("PFX");
        pedido.setP12File(new MockMultipartFile("p12File", "gps.pfx", "application/x-pkcs12", pfx));
        pedido.setPassword(senha);
        pedido.setShowSignature(false);
        pedido.setShowLogo(false);
        pedido.setPageNumber(1);
        return pedido;
    }

    private EventoDeAuditoria unicoEvento() throws Exception {
        List<EventoDeAuditoria> eventos = trilha.consultar(e -> true, 10);
        assertThat(eventos).hasSize(1);
        assertThat(trilha.verificar().integra()).isTrue();
        return eventos.get(0);
    }

    private static String arquivoDoMes() {
        return "assinaturas-" + Instant.now().toString().substring(0, 7) + ".jsonl";
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of()
                .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * e-CNPJ sintético: CNPJ no otherName 2.16.76.1.3.3 e outro número no CN, para provar que o
     * otherName tem prioridade.
     */
    private static byte[] pfxIcpBrasil() throws Exception {
        KeyPairGenerator gerador = KeyPairGenerator.getInstance("RSA");
        gerador.initialize(2048);
        KeyPair par = gerador.generateKeyPair();
        X500Name titular =
                new X500Name("CN=GPS CONTADORES TESTE:99999999000199, O=ICP-Brasil, C=BR");
        X500Name emissor = new X500Name("CN=AC GPS DE TESTE, O=ICP-Brasil, C=BR");
        Instant agora = Instant.now();
        JcaX509v3CertificateBuilder construtor =
                new JcaX509v3CertificateBuilder(
                        emissor,
                        BigInteger.valueOf(0x1092),
                        Date.from(agora.minus(1, ChronoUnit.DAYS)),
                        Date.from(agora.plus(30, ChronoUnit.DAYS)),
                        titular,
                        par.getPublic());
        construtor.addExtension(
                Extension.subjectAlternativeName,
                false,
                new GeneralNames(
                        new GeneralName(
                                GeneralName.otherName,
                                new DERSequence(
                                        new ASN1Encodable[] {
                                            new ASN1ObjectIdentifier("2.16.76.1.3.3"),
                                            new DERTaggedObject(
                                                    true, 0, new DEROctetString(CNPJ.getBytes()))
                                        }))));
        X509Certificate cert =
                new JcaX509CertificateConverter()
                        .getCertificate(
                                construtor.build(
                                        new JcaContentSignerBuilder("SHA256withRSA")
                                                .build(par.getPrivate())));
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("gps", par.getPrivate(), SENHA.toCharArray(), new Certificate[] {cert});
        ByteArrayOutputStream saida = new ByteArrayOutputStream();
        ks.store(saida, SENHA.toCharArray());
        return saida.toByteArray();
    }
}
