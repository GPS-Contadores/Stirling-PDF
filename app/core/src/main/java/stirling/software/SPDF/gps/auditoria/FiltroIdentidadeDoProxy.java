package stirling.software.SPDF.gps.auditoria;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Põe no MDC quem o oauth2-proxy diz que fez a requisição ({@link IdentidadeDoProxy}).
 *
 * <p>Só vale porque o Stirling não tem domínio público: no Railway, só o proxy chega nele, e o
 * proxy sobrescreve esses headers. Requisição sem eles segue normalmente; quem exige identidade (a
 * assinatura) decide o que fazer.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class FiltroIdentidadeDoProxy extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Thread reaproveitada não pode herdar o usuário da requisição anterior.
        limpar();
        colocar(IdentidadeDoProxy.MDC_EMAIL, request.getHeader("X-Forwarded-Email"));
        colocar(IdentidadeDoProxy.MDC_USUARIO, request.getHeader("X-Forwarded-User"));
        colocar(
                IdentidadeDoProxy.MDC_NOME_DE_USUARIO,
                request.getHeader("X-Forwarded-Preferred-Username"));
        colocar(IdentidadeDoProxy.MDC_GRUPOS, grupos(request));
        colocar(IdentidadeDoProxy.MDC_IP, ip(request));
        try {
            chain.doFilter(request, response);
        } finally {
            limpar();
        }
    }

    private static void limpar() {
        MDC.remove(IdentidadeDoProxy.MDC_EMAIL);
        MDC.remove(IdentidadeDoProxy.MDC_USUARIO);
        MDC.remove(IdentidadeDoProxy.MDC_NOME_DE_USUARIO);
        MDC.remove(IdentidadeDoProxy.MDC_GRUPOS);
        MDC.remove(IdentidadeDoProxy.MDC_IP);
    }

    /** O proxy pode mandar um header por grupo ou todos separados por vírgula. */
    private static String grupos(HttpServletRequest request) {
        List<String> grupos = new ArrayList<>();
        for (String valor : Collections.list(request.getHeaders("X-Forwarded-Groups"))) {
            for (String grupo : valor.split(",")) {
                if (!grupo.isBlank()) {
                    grupos.add(grupo.trim());
                }
            }
        }
        return String.join(",", grupos);
    }

    /**
     * A cadeia inteira do {@code X-Forwarded-For} (o primeiro é o cliente segundo a borda), ou o
     * endereço da conexão quando ninguém repassou.
     */
    private static String ip(HttpServletRequest request) {
        String encaminhado = request.getHeader("X-Forwarded-For");
        return encaminhado != null && !encaminhado.isBlank()
                ? encaminhado.trim()
                : request.getRemoteAddr();
    }

    private static void colocar(String chave, String valor) {
        if (valor != null && !valor.isBlank()) {
            MDC.put(chave, valor.trim());
        }
    }
}
