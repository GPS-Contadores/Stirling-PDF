package stirling.software.SPDF.gps.auditoria;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import stirling.software.SPDF.gps.auditoria.CertificadoDoPedido.Situacao;
import stirling.software.SPDF.model.api.security.SignPDFWithCertRequest;

class CertificadoDoPedidoTest {

    private static final String SENHA = "senha-de-teste";

    private static KeyPair parA;
    private static KeyPair parB;
    private static X509Certificate certA;
    private static X509Certificate certB;

    @BeforeAll
    static void gerar() throws Exception {
        KeyPairGenerator gerador = KeyPairGenerator.getInstance("RSA");
        gerador.initialize(2048);
        parA = gerador.generateKeyPair();
        parB = gerador.generateKeyPair();
        certA = autoassinado(parA, "EMPRESA A:11222333000181", 1);
        certB = autoassinado(parB, "EMPRESA B:44555666000199", 2);
    }

    private static X509Certificate autoassinado(KeyPair par, String cn, long serie)
            throws Exception {
        X500Name nome = new X500Name("CN=" + cn + ", O=Teste, C=BR");
        Instant agora = Instant.now();
        return new JcaX509CertificateConverter()
                .getCertificate(
                        new JcaX509v3CertificateBuilder(
                                        nome,
                                        BigInteger.valueOf(serie),
                                        Date.from(agora.minus(1, ChronoUnit.DAYS)),
                                        Date.from(agora.plus(30, ChronoUnit.DAYS)),
                                        nome,
                                        par.getPublic())
                                .build(
                                        new JcaContentSignerBuilder("SHA256withRSA")
                                                .build(par.getPrivate())));
    }

    /** Keystore com A no alias "a" e, se pedido, B no alias "b" (a ordem não é garantida). */
    private static byte[] keystore(String formato) throws Exception {
        return keystore(formato, true);
    }

    private static byte[] keystore(String formato, boolean comB) throws Exception {
        KeyStore ks = KeyStore.getInstance(formato);
        ks.load(null, null);
        ks.setKeyEntry("a", parA.getPrivate(), SENHA.toCharArray(), new Certificate[] {certA});
        if (comB) {
            ks.setKeyEntry("b", parB.getPrivate(), SENHA.toCharArray(), new Certificate[] {certB});
        }
        ByteArrayOutputStream saida = new ByteArrayOutputStream();
        ks.store(saida, SENHA.toCharArray());
        return saida.toByteArray();
    }

    private static SignPDFWithCertRequest pedido(String tipo, String senha) {
        SignPDFWithCertRequest pedido = new SignPDFWithCertRequest();
        pedido.setCertType(tipo);
        pedido.setPassword(senha);
        return pedido;
    }

    private static String cnpj(CertificadoDoPedido.Identificacao id) {
        return id.certificado() == null ? null : id.certificado().cpfCnpj();
    }

    @Test
    void pfxComASenhaCertaIdentificaOCertificado() throws Exception {
        SignPDFWithCertRequest pedido = pedido("PFX", SENHA);
        pedido.setP12File(new MockMultipartFile("p12File", keystore("PKCS12", false)));

        CertificadoDoPedido.Identificacao id = CertificadoDoPedido.identificar(pedido);

        assertThat(id.situacao()).isEqualTo(Situacao.IDENTIFICADO);
        assertThat(id.certificado().titular()).isEqualTo("EMPRESA A:11222333000181");
        assertThat(cnpj(id)).isEqualTo("11222333000181");
        assertThat(id.certificado().serie()).isEqualTo("1");
    }

    @Test
    void aliasPedidoEscolheOCertificadoComoOControlador() throws Exception {
        SignPDFWithCertRequest pedido = pedido("PKCS12", SENHA);
        pedido.setP12File(new MockMultipartFile("p12File", keystore("PKCS12")));
        pedido.setAlias("b");

        assertThat(cnpj(CertificadoDoPedido.identificar(pedido))).isEqualTo("44555666000199");
    }

    @Test
    void jksTambem() throws Exception {
        SignPDFWithCertRequest pedido = pedido("JKS", SENHA);
        pedido.setJksFile(new MockMultipartFile("jksFile", keystore("JKS")));
        pedido.setAlias("b");

        assertThat(cnpj(CertificadoDoPedido.identificar(pedido))).isEqualTo("44555666000199");
    }

    @Test
    void senhaErradaNaoEDesconhecido() throws Exception {
        SignPDFWithCertRequest pedido = pedido("PFX", "errada");
        pedido.setP12File(new MockMultipartFile("p12File", keystore("PKCS12")));

        assertThat(CertificadoDoPedido.identificar(pedido).situacao())
                .isEqualTo(Situacao.SENHA_ERRADA);
    }

    @Test
    void pemPeloArquivoDoCertificado() throws Exception {
        String pem =
                "-----BEGIN CERTIFICATE-----\n"
                        + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                                .encodeToString(certB.getEncoded())
                        + "\n-----END CERTIFICATE-----\n";
        SignPDFWithCertRequest pedido = pedido("PEM", SENHA);
        pedido.setCertFile(
                new MockMultipartFile("certFile", pem.getBytes(StandardCharsets.US_ASCII)));

        assertThat(cnpj(CertificadoDoPedido.identificar(pedido))).isEqualTo("44555666000199");
    }

    @Test
    void tipoSemSuporteOuArquivoIlegivelEDesconhecido() {
        assertThat(CertificadoDoPedido.identificar(pedido("SERVER", null)).situacao())
                .isEqualTo(Situacao.DESCONHECIDO);

        SignPDFWithCertRequest lixo = pedido("PFX", SENHA);
        lixo.setP12File(new MockMultipartFile("p12File", "não é keystore".getBytes()));
        assertThat(CertificadoDoPedido.identificar(lixo).situacao())
                .isEqualTo(Situacao.DESCONHECIDO);

        assertThat(CertificadoDoPedido.identificar(pedido("PFX", SENHA)).situacao())
                .isEqualTo(Situacao.DESCONHECIDO);
    }
}
