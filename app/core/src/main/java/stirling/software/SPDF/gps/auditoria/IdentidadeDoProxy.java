package stirling.software.SPDF.gps.auditoria;

import java.util.Arrays;
import java.util.List;

import org.slf4j.MDC;

/**
 * Quem fez a requisição, segundo o oauth2-proxy da borda: {@code X-Forwarded-Email}, {@code
 * X-Forwarded-User}, {@code X-Forwarded-Preferred-Username} e {@code X-Forwarded-Groups}.
 *
 * <p>Viaja pelo MDC, e não pelo request, porque o {@code AutoJobAspect} roda o {@code ?async=true}
 * numa thread de job onde o request já terminou; ele copia o MDC para essa thread.
 */
public record IdentidadeDoProxy(
        String email, String usuario, String nomeDeUsuario, List<String> grupos, String ip) {

    static final String MDC_EMAIL = "gps.proxy.email";
    static final String MDC_USUARIO = "gps.proxy.usuario";
    static final String MDC_NOME_DE_USUARIO = "gps.proxy.nomeDeUsuario";
    static final String MDC_GRUPOS = "gps.proxy.grupos";
    static final String MDC_IP = "gps.proxy.ip";

    /** Sem e-mail não há identidade: o proxy sempre repassa o e-mail do Entra. */
    public boolean presente() {
        return email != null && !email.isBlank();
    }

    static IdentidadeDoProxy doMdc() {
        String grupos = MDC.get(MDC_GRUPOS);
        return new IdentidadeDoProxy(
                MDC.get(MDC_EMAIL),
                MDC.get(MDC_USUARIO),
                MDC.get(MDC_NOME_DE_USUARIO),
                grupos == null || grupos.isBlank()
                        ? List.of()
                        : Arrays.stream(grupos.split(",")).map(String::trim).toList(),
                MDC.get(MDC_IP));
    }
}
