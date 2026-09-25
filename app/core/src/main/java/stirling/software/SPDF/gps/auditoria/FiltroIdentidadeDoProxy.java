package stirling.software.SPDF.gps.auditoria;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;

import org.eclipse.jetty.ee11.servlet.ServletContextRequest;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.extern.slf4j.Slf4j;

/**
 * Põe no MDC quem o oauth2-proxy diz que fez a requisição ({@link IdentidadeDoProxy}), se a conexão
 * veio do proxy.
 *
 * <p>O Stirling não tem domínio público, mas a rede privada do Railway é aberta aos outros serviços
 * do projeto (o {@code ofx}, que lê PDF de cliente, alcança o Stirling direto). Por isso os headers
 * de identidade só valem quando o par da conexão TCP é um dos endereços de {@code
 * gps.auditoria.proxy-confiavel} (variável {@code GPS_AUDITORIA_PROXYCONFIAVEL}, nomes ou IPs
 * separados por vírgula). Vazio não confia em ninguém; {@code *} confia em qualquer origem, só em
 * desenvolvimento.
 *
 * <p>O par vem do socket do Jetty, e não de {@code getRemoteAddr()}: com {@code
 * server.forward-headers-strategy=NATIVE} este devolve o {@code X-Forwarded-For}, que quem chama
 * escreve como quiser.
 *
 * <p>Requisição sem identidade confiável segue normalmente; quem exige identidade (a assinatura)
 * decide o que fazer.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@Slf4j
public class FiltroIdentidadeDoProxy extends OncePerRequestFilter {

    static final String QUALQUER_ORIGEM = "*";

    /** Um aviso por minuto e por origem: DNS fora do ar ou proxy mal configurado viram 1 linha. */
    private static final Duration INTERVALO_DOS_AVISOS = Duration.ofMinutes(1);

    /** {@link InetAddress#getAllByName}, trocável no teste. */
    @FunctionalInterface
    interface Resolvedor {
        InetAddress[] resolver(String nome) throws UnknownHostException;
    }

    private final List<String> origensConfiaveis;
    private final Function<HttpServletRequest, InetAddress> parDaConexao;
    private final Resolvedor resolvedor;
    private final AvisoComIntervalo avisos =
            new AvisoComIntervalo(INTERVALO_DOS_AVISOS, System::nanoTime);

    @Autowired
    public FiltroIdentidadeDoProxy(@Value("${gps.auditoria.proxy-confiavel:}") String origens) {
        this(origens, FiltroIdentidadeDoProxy::parNoJetty);
    }

    FiltroIdentidadeDoProxy(
            String origens, Function<HttpServletRequest, InetAddress> parDaConexao) {
        this(origens, parDaConexao, InetAddress::getAllByName);
    }

    FiltroIdentidadeDoProxy(
            String origens,
            Function<HttpServletRequest, InetAddress> parDaConexao,
            Resolvedor resolvedor) {
        this.origensConfiaveis =
                Arrays.stream((origens == null ? "" : origens).split(","))
                        .map(String::trim)
                        .filter(o -> !o.isEmpty())
                        .toList();
        this.parDaConexao = parDaConexao;
        this.resolvedor = resolvedor;
        if (origensConfiaveis.isEmpty()) {
            log.warn(
                    "gps.auditoria.proxy-confiavel vazio: a identidade do proxy é ignorada e a"
                            + " assinatura com certificado fica negada");
        }
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Thread reaproveitada não pode herdar o usuário da requisição anterior.
        limpar();
        InetAddress par = parDaConexao.apply(request);
        String enderecoDoPar = par != null ? par.getHostAddress() : "desconhecido";
        // Sem header de identidade não há o que conferir: CSS, JS e healthcheck não resolvem DNS.
        boolean trazIdentidade = request.getHeader("X-Forwarded-Email") != null;
        if (trazIdentidade && confiavel(par)) {
            colocar(IdentidadeDoProxy.MDC_EMAIL, request.getHeader("X-Forwarded-Email"));
            colocar(IdentidadeDoProxy.MDC_USUARIO, request.getHeader("X-Forwarded-User"));
            colocar(
                    IdentidadeDoProxy.MDC_NOME_DE_USUARIO,
                    request.getHeader("X-Forwarded-Preferred-Username"));
            colocar(IdentidadeDoProxy.MDC_GRUPOS, grupos(request));
            colocar(IdentidadeDoProxy.MDC_IP, ip(request, enderecoDoPar));
        } else {
            colocar(IdentidadeDoProxy.MDC_IP, enderecoDoPar);
            if (trazIdentidade) {
                colocar(IdentidadeDoProxy.MDC_ORIGEM_RECUSADA, enderecoDoPar);
                long suprimidos = avisos.avisar("recusada:" + enderecoDoPar);
                if (suprimidos >= 0) {
                    log.warn(
                            "Identidade do proxy recusada: a conexão veio de {}, fora de"
                                    + " gps.auditoria.proxy-confiavel ({} avisos iguais"
                                    + " suprimidos no último minuto)",
                            enderecoDoPar,
                            suprimidos);
                }
            }
        }
        try {
            chain.doFilter(request, response);
        } finally {
            limpar();
        }
    }

    /** O par está entre os endereços que os nomes configurados resolvem agora. */
    private boolean confiavel(InetAddress par) {
        if (par == null) {
            return false;
        }
        for (String origem : origensConfiaveis) {
            if (QUALQUER_ORIGEM.equals(origem)) {
                return true;
            }
            try {
                // O IP do proxy muda a cada deploy; a JVM guarda a resolução por 30 s.
                for (InetAddress endereco : resolvedor.resolver(origem)) {
                    if (endereco.equals(par)) {
                        return true;
                    }
                }
            } catch (UnknownHostException e) {
                long suprimidos = avisos.avisar("dns:" + origem);
                if (suprimidos >= 0) {
                    log.warn(
                            "gps.auditoria.proxy-confiavel: {} não resolve ({} avisos iguais"
                                    + " suprimidos no último minuto)",
                            origem,
                            suprimidos);
                }
            }
        }
        return false;
    }

    /** O endereço do socket, que o {@code X-Forwarded-For} não altera; nulo fora do Jetty. */
    static InetAddress parNoJetty(HttpServletRequest request) {
        try {
            ServletContextRequest jetty = ServletContextRequest.getServletContextRequest(request);
            if (jetty != null
                    && jetty.getConnectionMetaData()
                                    .getConnection()
                                    .getEndPoint()
                                    .getRemoteSocketAddress()
                            instanceof InetSocketAddress socket) {
                return socket.getAddress();
            }
        } catch (RuntimeException e) {
            log.warn("Não foi possível ler o par da conexão", e);
        }
        return null;
    }

    private static void limpar() {
        MDC.remove(IdentidadeDoProxy.MDC_EMAIL);
        MDC.remove(IdentidadeDoProxy.MDC_USUARIO);
        MDC.remove(IdentidadeDoProxy.MDC_NOME_DE_USUARIO);
        MDC.remove(IdentidadeDoProxy.MDC_GRUPOS);
        MDC.remove(IdentidadeDoProxy.MDC_IP);
        MDC.remove(IdentidadeDoProxy.MDC_ORIGEM_RECUSADA);
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
     * A cadeia inteira do {@code X-Forwarded-For}, ou o par da conexão quando ninguém repassou.
     *
     * <p>Os primeiros itens podem ter sido escritos pelo próprio cliente: o oauth2-proxy mantém o
     * {@code X-Forwarded-For} recebido e só acrescenta o dele (visto no v7.15.4). Confiáveis são os
     * itens acrescentados pela borda do Railway e pelo proxy, no fim da cadeia.
     */
    private static String ip(HttpServletRequest request, String enderecoDoPar) {
        String encaminhado = request.getHeader("X-Forwarded-For");
        return encaminhado != null && !encaminhado.isBlank() ? encaminhado.trim() : enderecoDoPar;
    }

    /** No máximo um aviso por chave a cada intervalo, contando os que ficaram de fora. */
    static final class AvisoComIntervalo {
        private final long intervaloNanos;
        private final LongSupplier relogio;

        /** Por chave: nanoTime do último aviso e quantos foram suprimidos desde ele. */
        private final ConcurrentHashMap<String, long[]> estado = new ConcurrentHashMap<>();

        AvisoComIntervalo(Duration intervalo, LongSupplier relogio) {
            this.intervaloNanos = intervalo.toNanos();
            this.relogio = relogio;
        }

        /** -1 se não é hora de avisar; senão, quantos avisos iguais foram suprimidos antes. */
        long avisar(String chave) {
            long agora = relogio.getAsLong();
            long[] suprimidos = {-1};
            estado.compute(
                    chave,
                    (k, anterior) -> {
                        if (anterior == null || agora - anterior[0] >= intervaloNanos) {
                            suprimidos[0] = anterior == null ? 0 : anterior[1];
                            return new long[] {agora, 0};
                        }
                        anterior[1]++;
                        return anterior;
                    });
            return suprimidos[0];
        }
    }

    private static void colocar(String chave, String valor) {
        if (valor != null && !valor.isBlank()) {
            MDC.put(chave, valor.trim());
        }
    }
}
