package stirling.software.SPDF.gps.auditoria;

import java.util.List;

/**
 * Uma linha da trilha. Nunca leva o conteúdo do documento nem a senha do certificado.
 *
 * <p>{@code id}, {@code quando} e {@code hashAnterior} ficam nulos até a {@link TrilhaDeAuditoria}
 * gravar o evento: é ela quem carimba, para a ordem das linhas ser a ordem da cadeia de hash.
 */
public record EventoDeAuditoria(
        String id,
        String quando,
        Usuario usuario,
        String ferramenta,
        Certificado certificado,
        Documento documento,
        String resultado,
        String motivo,
        String hashAnterior) {

    public static final String SUCESSO = "sucesso";
    public static final String ERRO = "erro";
    public static final String NEGADO = "negado";

    /** {@code usuario} é o {@code X-Forwarded-User} (id no Entra); {@code ip}, o do proxy. */
    public record Usuario(
            String email, String usuario, String nomeDeUsuario, List<String> grupos, String ip) {

        static Usuario de(IdentidadeDoProxy identidade) {
            return new Usuario(
                    identidade.email(),
                    identidade.usuario(),
                    identidade.nomeDeUsuario(),
                    identidade.grupos(),
                    identidade.ip());
        }
    }

    /**
     * Os dados do certificado saem da assinatura gravada no PDF; em erro ou negativa só se conhece
     * {@code arquivoSha256}, o hash do arquivo de certificado enviado.
     */
    public record Certificado(
            String titular,
            String cpfCnpj,
            String serie,
            String emissor,
            String validoAte,
            String sha256,
            String arquivoSha256) {}

    public record Documento(
            String nome, String sha256Antes, String sha256Depois, Long bytesDepois) {}

    EventoDeAuditoria carimbar(String id, String quando, String hashAnterior) {
        return new EventoDeAuditoria(
                id,
                quando,
                usuario,
                ferramenta,
                certificado,
                documento,
                resultado,
                motivo,
                hashAnterior);
    }
}
