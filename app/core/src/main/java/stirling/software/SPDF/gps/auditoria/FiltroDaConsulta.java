package stirling.software.SPDF.gps.auditoria;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Filtro da consulta da trilha. Campo nulo ou vazio não filtra.
 *
 * <p>{@code certificado} casa com CPF/CNPJ (só dígitos), série, SHA-256 do certificado ou do
 * arquivo, ou trecho do titular. {@code documento} casa com o SHA-256 de antes ou de depois, ou
 * trecho do nome. {@code de} e {@code ate} são dias no horário de Brasília, inclusivos.
 */
record FiltroDaConsulta(
        String usuario, String certificado, String documento, LocalDate de, LocalDate ate)
        implements Predicate<EventoDeAuditoria> {

    private static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");

    @Override
    public boolean test(EventoDeAuditoria evento) {
        return casaUsuario(evento)
                && casaCertificado(evento)
                && casaDocumento(evento)
                && casaPeriodo(evento);
    }

    private boolean casaUsuario(EventoDeAuditoria evento) {
        if (vazio(usuario)) {
            return true;
        }
        EventoDeAuditoria.Usuario u = evento.usuario();
        return u != null && (contem(u.email(), usuario) || contem(u.nomeDeUsuario(), usuario));
    }

    private boolean casaCertificado(EventoDeAuditoria evento) {
        if (vazio(certificado)) {
            return true;
        }
        EventoDeAuditoria.Certificado c = evento.certificado();
        if (c == null) {
            return false;
        }
        String digitos = certificado.replaceAll("\\D", "");
        return (!digitos.isEmpty() && digitos.equals(c.cpfCnpj()))
                || certificado.trim().equalsIgnoreCase(c.serie())
                || certificado.trim().equalsIgnoreCase(c.sha256())
                || certificado.trim().equalsIgnoreCase(c.arquivoSha256())
                || contem(c.titular(), certificado);
    }

    private boolean casaDocumento(EventoDeAuditoria evento) {
        if (vazio(documento)) {
            return true;
        }
        EventoDeAuditoria.Documento d = evento.documento();
        return d != null
                && (documento.trim().equalsIgnoreCase(d.sha256Antes())
                        || documento.trim().equalsIgnoreCase(d.sha256Depois())
                        || contem(d.nome(), documento));
    }

    private boolean casaPeriodo(EventoDeAuditoria evento) {
        if (de == null && ate == null) {
            return true;
        }
        Instant quando;
        try {
            quando = Instant.parse(evento.quando());
        } catch (RuntimeException e) {
            // Data adulterada ou ausente: fica fora do período, e verificar() acusa a edição.
            return false;
        }
        return (de == null || !quando.isBefore(de.atStartOfDay(BRASILIA).toInstant()))
                && (ate == null
                        || quando.isBefore(ate.plusDays(1).atStartOfDay(BRASILIA).toInstant()));
    }

    private static boolean contem(String valor, String trecho) {
        return valor != null
                && valor.toLowerCase(Locale.ROOT).contains(trecho.trim().toLowerCase(Locale.ROOT));
    }

    private static boolean vazio(String valor) {
        return valor == null || valor.isBlank();
    }
}
