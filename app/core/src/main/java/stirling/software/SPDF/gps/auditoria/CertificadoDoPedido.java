package stirling.software.SPDF.gps.auditoria;

import java.io.InputStream;
import java.security.KeyStore;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Enumeration;
import java.util.Locale;

import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.springframework.web.multipart.MultipartFile;

import stirling.software.SPDF.model.api.security.SignPDFWithCertRequest;

/**
 * Qual certificado o {@code cert-sign} vai usar, lido antes de assinar para a {@link
 * ListaDeCertificados} decidir.
 *
 * <p>Repete a escolha do {@code CertSignController}: PKCS12/PFX e JKS abertos com a senha, com o
 * alias pedido ou, sem ele, a primeira entrada com cadeia ({@code CreateSignatureBase}); PEM pelo
 * {@code certFile}, com o mesmo {@code CertificateFactory}. Depois da assinatura, o aspecto confere
 * se o certificado gravado no PDF é este.
 */
final class CertificadoDoPedido {

    enum Situacao {
        /** Certificado lido; {@link Identificacao#certificado()} preenchido. */
        IDENTIFICADO,
        /**
         * Senha errada: o controlador vai falhar do mesmo jeito, e o erro real vai para a trilha (a
         * #20 conta essas falhas). Não é negativa.
         */
        SENHA_ERRADA,
        /** Tipo fora da lista (SERVER, WINDOWS_STORE, PKCS11) ou arquivo que não se lê. */
        DESCONHECIDO
    }

    record Identificacao(Situacao situacao, EventoDeAuditoria.Certificado certificado) {

        static Identificacao desconhecido() {
            return new Identificacao(Situacao.DESCONHECIDO, null);
        }
    }

    private CertificadoDoPedido() {}

    static Identificacao identificar(SignPDFWithCertRequest pedido) {
        String tipo =
                pedido.getCertType() == null
                        ? ""
                        : pedido.getCertType().trim().toUpperCase(Locale.ROOT);
        try {
            X509Certificate cert =
                    switch (tipo) {
                        case "PKCS12", "PFX" -> doKeystore("PKCS12", pedido.getP12File(), pedido);
                        case "JKS" -> doKeystore("JKS", pedido.getJksFile(), pedido);
                        case "PEM" -> doArquivo(pedido.getCertFile());
                        default -> null;
                    };
            return cert == null
                    ? Identificacao.desconhecido()
                    : new Identificacao(Situacao.IDENTIFICADO, descrever(cert));
        } catch (Exception e) {
            return temCausa(e, UnrecoverableKeyException.class)
                    ? new Identificacao(Situacao.SENHA_ERRADA, null)
                    : Identificacao.desconhecido();
        }
    }

    private static X509Certificate doKeystore(
            String formato, MultipartFile arquivo, SignPDFWithCertRequest pedido) throws Exception {
        if (arquivo == null || arquivo.isEmpty()) {
            return null;
        }
        char[] senha = pedido.getPassword() == null ? null : pedido.getPassword().toCharArray();
        KeyStore ks = KeyStore.getInstance(formato);
        try (InputStream in = arquivo.getInputStream()) {
            ks.load(in, senha);
        } finally {
            if (senha != null) {
                java.util.Arrays.fill(senha, '\0');
            }
        }
        String alias = pedido.getAlias();
        if (alias != null && !alias.isBlank() && ks.containsAlias(alias)) {
            return primeiro(ks, alias);
        }
        Enumeration<String> aliases = ks.aliases();
        while (aliases.hasMoreElements()) {
            X509Certificate cert = primeiro(ks, aliases.nextElement());
            if (cert != null) {
                return cert;
            }
        }
        return null;
    }

    private static X509Certificate primeiro(KeyStore ks, String alias) throws Exception {
        Certificate[] cadeia = ks.getCertificateChain(alias);
        Certificate cert =
                cadeia != null && cadeia.length > 0 ? cadeia[0] : ks.getCertificate(alias);
        return cert instanceof X509Certificate x509 ? x509 : null;
    }

    private static X509Certificate doArquivo(MultipartFile arquivo) throws Exception {
        if (arquivo == null || arquivo.isEmpty()) {
            return null;
        }
        try (InputStream in = arquivo.getInputStream()) {
            return CertificateFactory.getInstance("X.509").generateCertificate(in)
                            instanceof X509Certificate x509
                    ? x509
                    : null;
        }
    }

    static EventoDeAuditoria.Certificado descrever(X509Certificate cert)
            throws CertificateEncodingException {
        return AssinaturaDoPdf.descrever(new JcaX509CertificateHolder(cert));
    }

    private static boolean temCausa(Throwable erro, Class<? extends Throwable> tipo) {
        for (Throwable t = erro; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (tipo.isInstance(t)) {
                return true;
            }
        }
        return false;
    }
}
