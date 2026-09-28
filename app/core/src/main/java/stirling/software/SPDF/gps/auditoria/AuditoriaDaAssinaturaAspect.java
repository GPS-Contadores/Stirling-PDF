package stirling.software.SPDF.gps.auditoria;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import lombok.extern.slf4j.Slf4j;

import stirling.software.SPDF.model.api.security.SignPDFWithCertRequest;

/**
 * Registra na {@link TrilhaDeAuditoria} toda chamada à assinatura com certificado ({@code POST
 * /api/v1/security/cert-sign}): sucesso, erro ou negada.
 *
 * <p>Nega antes de assinar quem não tem identidade do proxy e quem a {@link ListaDeCertificados}
 * não libera para o certificado do pedido ({@link CertificadoDoPedido}, #19). Depois de assinar,
 * confere se o PDF saiu assinado com o certificado autorizado.
 *
 * <p>{@code @Order(30)} põe este aspecto por dentro do {@code AutoJobAspect} ({@code @Order(20)}):
 * com {@code ?async=true} ele roda na thread do job, com o MDC (a identidade) já copiado e o
 * resultado real em mãos.
 *
 * <p>Se a trilha não conseguir gravar, a assinatura falha: toda assinatura entregue tem de estar na
 * trilha.
 */
@Aspect
@Component
@Order(30)
@Slf4j
public class AuditoriaDaAssinaturaAspect {

    static final String SEM_IDENTIDADE = "requisição sem identidade do proxy (X-Forwarded-Email)";
    static final String IDENTIDADE_FORA_DO_PROXY =
            "identidade recusada: a conexão não veio do proxy";
    static final String SEM_ASSINATURA_NOVA = "o PDF devolvido não traz assinatura nova";
    static final String SAIDA_ILEGIVEL = "não foi possível conferir o PDF assinado";
    static final String OUTRO_CERTIFICADO =
            "o PDF foi assinado com outro certificado, não o autorizado";
    private static final int TAMANHO_MAXIMO_DO_MOTIVO = 300;

    private final TrilhaDeAuditoria trilha;
    private final PapeisDoUsuario papeis;
    private final ListaDeCertificados certificados;
    private final boolean exigirIdentidade;

    public AuditoriaDaAssinaturaAspect(
            TrilhaDeAuditoria trilha,
            PapeisDoUsuario papeis,
            ListaDeCertificados certificados,
            @Value("${gps.auditoria.exigir-identidade:true}") boolean exigirIdentidade) {
        this.trilha = trilha;
        this.papeis = papeis;
        this.certificados = certificados;
        this.exigirIdentidade = exigirIdentidade;
    }

    /**
     * Sem {@code args(...)} no pointcut, de propósito: binding de argumento deixa o casamento
     * dinâmico, e o Spring o resolve por ThreadLocal. O {@code AutoJobAspect} chama o {@code
     * proceed()} numa thread de job, onde essa ThreadLocal não existe, e toda assinatura quebrava
     * com "JoinPointMatch was NOT bound in invocation".
     */
    @Around(
            "execution(*"
                    + " stirling.software.SPDF.controller.api.security.CertSignController.signPDFWithCert(..))")
    public Object auditar(ProceedingJoinPoint ponto) throws Throwable {
        SignPDFWithCertRequest pedido = pedido(ponto.getArgs());
        IdentidadeDoProxy identidade = IdentidadeDoProxy.doMdc();
        MultipartFile pdf = pedido.getFileInput();
        EventoDeAuditoria.Documento antes =
                new EventoDeAuditoria.Documento(
                        pdf != null ? pdf.getOriginalFilename() : null, sha256(pdf), null, null);
        EventoDeAuditoria.Certificado arquivoDoCertificado =
                new EventoDeAuditoria.Certificado(
                        null, null, null, null, null, null, sha256(arquivoDoCertificado(pedido)));
        Registro registro = new Registro(identidade, ferramenta(pedido));

        if (!identidade.presente() && exigirIdentidade) {
            String origemRecusada = IdentidadeDoProxy.origemRecusada();
            registro.gravar(
                    arquivoDoCertificado,
                    antes,
                    EventoDeAuditoria.NEGADO,
                    origemRecusada != null
                            ? IDENTIDADE_FORA_DO_PROXY + " (" + origemRecusada + ")"
                            : SEM_IDENTIDADE);
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "A assinatura exige login pelo GPS Documentos.");
        }

        // Quem pode usar este certificado (#19). Com senha errada não há o que decidir: o
        // controlador falha do mesmo jeito, e o erro real vai para a trilha.
        CertificadoDoPedido.Identificacao pedidoDe = CertificadoDoPedido.identificar(pedido);
        EventoDeAuditoria.Certificado autorizado =
                pedidoDe.certificado() != null
                        ? comArquivo(pedidoDe.certificado(), arquivoDoCertificado.arquivoSha256())
                        : null;
        if (pedidoDe.situacao() != CertificadoDoPedido.Situacao.SENHA_ERRADA) {
            ListaDeCertificados.Decisao decisao =
                    certificados.decidir(autorizado, identidade, papeis.de(identidade));
            if (!decisao.permitido()) {
                registro.gravar(
                        autorizado != null ? autorizado : arquivoDoCertificado,
                        antes,
                        EventoDeAuditoria.NEGADO,
                        decisao.motivo());
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN, "Sem permissão para assinar com este certificado.");
            }
        }

        Object resultado;
        try {
            resultado = ponto.proceed();
        } catch (Throwable falha) {
            gravarErro(registro, arquivoDoCertificado, antes, motivo(falha), falha);
            throw falha;
        }

        Path saida;
        Optional<EventoDeAuditoria.Certificado> certificado;
        EventoDeAuditoria.Documento depois;
        try {
            saida = arquivoDaResposta(resultado);
            certificado =
                    saida != null
                            ? AssinaturaDoPdf.daUltimaAssinatura(saida, tamanho(pdf))
                            : Optional.empty();
            depois =
                    saida != null
                            ? new EventoDeAuditoria.Documento(
                                    antes.nome(),
                                    antes.sha256Antes(),
                                    sha256(saida),
                                    Files.size(saida))
                            : antes;
        } catch (IOException | RuntimeException falha) {
            // A assinatura não é entregue, mas a chamada não pode sumir da trilha.
            gravarErro(
                    registro,
                    arquivoDoCertificado,
                    antes,
                    SAIDA_ILEGIVEL + ": " + motivo(falha),
                    falha);
            throw falha;
        }
        if (certificado.isPresent()
                && autorizado != null
                && !autorizado.sha256().equalsIgnoreCase(certificado.get().sha256())) {
            // A permissão valeu para um certificado e o PDF saiu assinado com outro: não entrega.
            EventoDeAuditoria.Certificado usado =
                    comArquivo(certificado.get(), arquivoDoCertificado.arquivoSha256());
            registro.gravar(usado, depois, EventoDeAuditoria.ERRO, OUTRO_CERTIFICADO);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, OUTRO_CERTIFICADO);
        }
        if (certificado.isPresent()) {
            registro.gravar(
                    comArquivo(certificado.get(), arquivoDoCertificado.arquivoSha256()),
                    depois,
                    EventoDeAuditoria.SUCESSO,
                    null);
        } else {
            registro.gravar(
                    arquivoDoCertificado, depois, EventoDeAuditoria.ERRO, SEM_ASSINATURA_NOVA);
        }
        return resultado;
    }

    private static EventoDeAuditoria.Certificado comArquivo(
            EventoDeAuditoria.Certificado c, String arquivoSha256) {
        return new EventoDeAuditoria.Certificado(
                c.titular(),
                c.cpfCnpj(),
                c.serie(),
                c.emissor(),
                c.validoAte(),
                c.sha256(),
                arquivoSha256);
    }

    /** O que é comum às linhas de uma mesma chamada. */
    private final class Registro {
        private final IdentidadeDoProxy identidade;
        private final String ferramenta;

        Registro(IdentidadeDoProxy identidade, String ferramenta) {
            this.identidade = identidade;
            this.ferramenta = ferramenta;
        }

        void gravar(
                EventoDeAuditoria.Certificado certificado,
                EventoDeAuditoria.Documento documento,
                String resultado,
                String motivo)
                throws IOException {
            try {
                trilha.registrar(
                        new EventoDeAuditoria(
                                null,
                                null,
                                EventoDeAuditoria.Usuario.de(identidade),
                                ferramenta,
                                certificado,
                                documento,
                                resultado,
                                motivo,
                                null));
            } catch (IOException e) {
                log.error("Trilha de auditoria não gravou a assinatura ({})", resultado, e);
                throw e;
            }
        }
    }

    /** Grava o erro sem esconder a falha original se a própria gravação falhar. */
    private static void gravarErro(
            Registro registro,
            EventoDeAuditoria.Certificado certificado,
            EventoDeAuditoria.Documento documento,
            String motivo,
            Throwable falha) {
        try {
            registro.gravar(certificado, documento, EventoDeAuditoria.ERRO, motivo);
        } catch (IOException naoGravou) {
            falha.addSuppressed(naoGravou);
        }
    }

    private static long tamanho(MultipartFile arquivo) {
        return arquivo != null ? arquivo.getSize() : 0;
    }

    private static SignPDFWithCertRequest pedido(Object[] argumentos) {
        for (Object argumento : argumentos) {
            if (argumento instanceof SignPDFWithCertRequest pedido) {
                return pedido;
            }
        }
        throw new IllegalStateException("cert-sign chamado sem SignPDFWithCertRequest");
    }

    private static String ferramenta(SignPDFWithCertRequest pedido) {
        String tipo = pedido.getCertType();
        return "cert-sign/" + (tipo == null || tipo.isBlank() ? "?" : tipo.trim().toUpperCase());
    }

    private static MultipartFile arquivoDoCertificado(SignPDFWithCertRequest pedido) {
        if (pedido.getP12File() != null) {
            return pedido.getP12File();
        }
        if (pedido.getJksFile() != null) {
            return pedido.getJksFile();
        }
        return pedido.getCertFile();
    }

    /**
     * O corpo do {@code cert-sign} é um {@code ManagedTempFileResource} (arquivo em disco). Lê-se
     * pelo caminho: abrir o stream dele apagaria o arquivo antes de chegar ao cliente.
     */
    private static Path arquivoDaResposta(Object resultado) {
        if (resultado instanceof ResponseEntity<?> resposta
                && resposta.getBody() instanceof FileSystemResource arquivo
                && arquivo.exists()) {
            return arquivo.getFile().toPath();
        }
        return null;
    }

    /** A mensagem de exceção de keystore não leva a senha; mesmo assim, só classe e mensagem. */
    private static String motivo(Throwable falha) {
        Throwable raiz = falha;
        while (raiz.getCause() != null && raiz.getCause() != raiz) {
            raiz = raiz.getCause();
        }
        String motivo =
                raiz.getClass().getSimpleName()
                        + (raiz.getMessage() != null ? ": " + raiz.getMessage() : "");
        return motivo.length() > TAMANHO_MAXIMO_DO_MOTIVO
                ? motivo.substring(0, TAMANHO_MAXIMO_DO_MOTIVO)
                : motivo;
    }

    private static String sha256(MultipartFile arquivo) {
        if (arquivo == null || arquivo.isEmpty()) {
            return null;
        }
        try (InputStream in = arquivo.getInputStream()) {
            return sha256(in);
        } catch (IOException e) {
            // Com ?async=true o upload do certificado pode já ter sido descartado.
            return null;
        }
    }

    private static String sha256(Path arquivo) throws IOException {
        try (InputStream in = Files.newInputStream(arquivo)) {
            return sha256(in);
        }
    }

    private static String sha256(InputStream in) throws IOException {
        MessageDigest digest = TrilhaDeAuditoria.digest();
        try (DigestInputStream leitor = new DigestInputStream(in, digest)) {
            leitor.transferTo(OutputStreamNulo.INSTANCIA);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static final class OutputStreamNulo extends java.io.OutputStream {
        static final OutputStreamNulo INSTANCIA = new OutputStreamNulo();

        @Override
        public void write(int b) {}

        @Override
        public void write(byte[] b, int off, int len) {}
    }
}
