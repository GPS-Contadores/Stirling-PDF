package stirling.software.SPDF.gps.auditoria;

import java.io.IOException;
import java.security.KeyStore;
import java.security.Principal;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;

import lombok.extern.slf4j.Slf4j;

/**
 * Registra na {@link TrilhaDeAuditoria} a assinatura com certificado que não passa pelo {@code
 * cert-sign}: a finalização de sessão de assinatura ({@code POST
 * /api/v1/security/cert-sign/sessions/{id}/finalize}), que assina participante a participante pelo
 * {@code PdfSigningService.signWithKeystore}.
 *
 * <p>O {@code CertificateSubmissionValidator} também chama o {@code signWithKeystore}, num PDF em
 * branco, para testar o certificado enviado. Isso não é assinatura entregue e não vai para a
 * trilha: o método público dele marca a thread enquanto valida.
 *
 * <p>A identidade é o {@code Principal} do login do próprio Stirling, que a sessão exige, e não os
 * headers do proxy. Sem ele a assinatura é negada, como no {@code cert-sign}.
 *
 * <p>Os dois pointcuts nomeiam as classes por texto: o {@code app/core} compila sem o {@code
 * proprietary} quando {@code DISABLE_ADDITIONAL_FEATURES=true}, e aí o do validador simplesmente
 * não casa com nada. Sem {@code args(...)}, pelo mesmo motivo do {@link
 * AuditoriaDaAssinaturaAspect}.
 */
@Aspect
@Component
@Slf4j
public class AuditoriaDaSessaoDeAssinaturaAspect {

    static final String SEM_LOGIN = "assinatura sem usuário logado no Stirling";
    static final String SEM_ASSINATURA_NOVA = AuditoriaDaAssinaturaAspect.SEM_ASSINATURA_NOVA;

    private static final Pattern FINALIZACAO =
            Pattern.compile("/cert-sign/sessions/([^/]+)/finalize/?$");

    private final ThreadLocal<Boolean> validando = new ThreadLocal<>();
    private final TrilhaDeAuditoria trilha;
    private final boolean exigirIdentidade;

    public AuditoriaDaSessaoDeAssinaturaAspect(
            TrilhaDeAuditoria trilha,
            @Value("${gps.auditoria.exigir-identidade:true}") boolean exigirIdentidade) {
        this.trilha = trilha;
        this.exigirIdentidade = exigirIdentidade;
    }

    @Around(
            "execution(*"
                    + " stirling.software.proprietary.workflow.service.CertificateSubmissionValidator.validateAndExtractInfo(..))")
    public Object marcarValidacao(ProceedingJoinPoint ponto) throws Throwable {
        Boolean antes = validando.get();
        validando.set(Boolean.TRUE);
        try {
            return ponto.proceed();
        } finally {
            if (antes == null) {
                validando.remove();
            }
        }
    }

    @Around("execution(* stirling.software.common.service.PdfSigningService+.signWithKeystore(..))")
    public Object auditar(ProceedingJoinPoint ponto) throws Throwable {
        if (validando.get() != null) {
            return ponto.proceed();
        }
        Object[] argumentos = ponto.getArgs();
        byte[] entrada = argumentos.length > 0 ? (byte[]) argumentos[0] : null;
        KeyStore keystore =
                argumentos.length > 1 && argumentos[1] instanceof KeyStore ks ? ks : null;
        HttpServletRequest requisicao = requisicao();
        Principal principal = requisicao != null ? requisicao.getUserPrincipal() : null;
        String nome = principal != null ? principal.getName() : null;
        EventoDeAuditoria.Usuario usuario =
                new EventoDeAuditoria.Usuario(
                        nome != null && nome.contains("@") ? nome : null,
                        null,
                        nome,
                        List.of(),
                        IdentidadeDoProxy.doMdc().ip());
        String ferramenta =
                "cert-sign/sessao/" + (keystore != null ? keystore.getType().toUpperCase() : "?");
        EventoDeAuditoria.Documento antes =
                new EventoDeAuditoria.Documento(
                        nomeDaSessao(requisicao),
                        entrada != null ? TrilhaDeAuditoria.sha256(entrada) : null,
                        null,
                        null);
        EventoDeAuditoria.Certificado semCertificado =
                new EventoDeAuditoria.Certificado(null, null, null, null, null, null, null);

        if ((nome == null || nome.isBlank()) && exigirIdentidade) {
            gravar(usuario, ferramenta, semCertificado, antes, EventoDeAuditoria.NEGADO, SEM_LOGIN);
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "A assinatura exige usuário logado.");
        }

        Object resultado;
        try {
            resultado = ponto.proceed();
        } catch (Throwable falha) {
            try {
                gravar(
                        usuario,
                        ferramenta,
                        semCertificado,
                        antes,
                        EventoDeAuditoria.ERRO,
                        AuditoriaDaAssinaturaAspect.motivo(falha));
            } catch (IOException naoGravou) {
                falha.addSuppressed(naoGravou);
            }
            throw falha;
        }

        byte[] saida = resultado instanceof byte[] bytes ? bytes : null;
        Optional<EventoDeAuditoria.Certificado> certificado =
                AssinaturaDoPdf.daUltimaAssinatura(saida, entrada != null ? entrada.length : 0);
        EventoDeAuditoria.Documento depois =
                saida != null
                        ? new EventoDeAuditoria.Documento(
                                antes.nome(),
                                antes.sha256Antes(),
                                TrilhaDeAuditoria.sha256(saida),
                                (long) saida.length)
                        : antes;
        if (certificado.isPresent()) {
            gravar(usuario, ferramenta, certificado.get(), depois, EventoDeAuditoria.SUCESSO, null);
        } else {
            gravar(
                    usuario,
                    ferramenta,
                    semCertificado,
                    depois,
                    EventoDeAuditoria.ERRO,
                    SEM_ASSINATURA_NOVA);
        }
        return resultado;
    }

    private void gravar(
            EventoDeAuditoria.Usuario usuario,
            String ferramenta,
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
                            usuario,
                            ferramenta,
                            certificado,
                            documento,
                            resultado,
                            motivo,
                            null));
        } catch (IOException e) {
            log.error("Trilha de auditoria não gravou a assinatura de sessão ({})", resultado, e);
            throw e;
        }
    }

    private static HttpServletRequest requisicao() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a
                ? a.getRequest()
                : null;
    }

    /**
     * O nome do documento fica no {@code WorkflowSession}, do {@code proprietary}, que o core não
     * pode importar; a linha leva o id da sessão, tirado da URL da finalização.
     */
    private static String nomeDaSessao(HttpServletRequest requisicao) {
        if (requisicao == null || requisicao.getRequestURI() == null) {
            return null;
        }
        Matcher m = FINALIZACAO.matcher(requisicao.getRequestURI());
        return m.find() ? "sessão " + m.group(1) : null;
    }
}
