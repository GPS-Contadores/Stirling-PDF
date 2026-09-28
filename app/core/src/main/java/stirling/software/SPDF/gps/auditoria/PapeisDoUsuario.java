package stirling.software.SPDF.gps.auditoria;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Papéis de quem fez a requisição, tirados dos grupos que o oauth2-proxy repassa em {@code
 * X-Forwarded-Groups} (App Roles do Entra, com {@code OAUTH2_PROXY_OIDC_GROUPS_CLAIM=roles}).
 *
 * <p>Os grupos só chegam aqui vindos do proxy ({@link FiltroIdentidadeDoProxy}). Os nomes são
 * configuráveis e comparados sem diferença de maiúsculas. Enquanto os App Roles não existem, os
 * e-mails de {@code gps.auditoria.leitores} continuam contando como auditor.
 */
@Component
public class PapeisDoUsuario {

    /**
     * {@code admin} inclui {@code auditor} e {@code assinante}; todo usuário identificado é {@code
     * usuario}.
     */
    public enum Papel {
        ADMIN,
        AUDITOR,
        ASSINANTE,
        USUARIO;

        /** Nome como aparece na lista de certificados ({@code papel:assinante}). */
        String nome() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final String grupoAdmin;
    private final String grupoAuditor;
    private final String grupoAssinante;
    private final Set<String> leitores;

    public PapeisDoUsuario(
            @Value("${gps.papeis.grupo-admin:Documentos.Admin}") String grupoAdmin,
            @Value("${gps.papeis.grupo-auditor:Documentos.Auditor}") String grupoAuditor,
            @Value("${gps.papeis.grupo-assinante:Documentos.Assinante}") String grupoAssinante,
            @Value("${gps.auditoria.leitores:}") String leitores) {
        this.grupoAdmin = normalizar(grupoAdmin);
        this.grupoAuditor = normalizar(grupoAuditor);
        this.grupoAssinante = normalizar(grupoAssinante);
        this.leitores =
                Arrays.stream(leitores == null ? new String[0] : leitores.split(","))
                        .map(PapeisDoUsuario::normalizar)
                        .filter(e -> !e.isEmpty())
                        .collect(Collectors.toUnmodifiableSet());
    }

    /** Vazio para quem não tem identidade do proxy. */
    public Set<Papel> de(IdentidadeDoProxy identidade) {
        if (identidade == null || !identidade.presente()) {
            return EnumSet.noneOf(Papel.class);
        }
        Set<String> grupos =
                identidade.grupos().stream()
                        .map(PapeisDoUsuario::normalizar)
                        .collect(Collectors.toSet());
        Set<Papel> papeis = EnumSet.of(Papel.USUARIO);
        if (!grupoAdmin.isEmpty() && grupos.contains(grupoAdmin)) {
            papeis.add(Papel.ADMIN);
            papeis.add(Papel.AUDITOR);
            papeis.add(Papel.ASSINANTE);
        }
        if ((!grupoAuditor.isEmpty() && grupos.contains(grupoAuditor))
                || leitores.contains(normalizar(identidade.email()))) {
            papeis.add(Papel.AUDITOR);
        }
        if (!grupoAssinante.isEmpty() && grupos.contains(grupoAssinante)) {
            papeis.add(Papel.ASSINANTE);
        }
        return papeis;
    }

    private static String normalizar(String valor) {
        return valor == null ? "" : valor.trim().toLowerCase(Locale.ROOT);
    }
}
