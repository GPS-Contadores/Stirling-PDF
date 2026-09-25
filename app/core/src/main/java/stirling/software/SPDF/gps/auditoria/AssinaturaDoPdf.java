package stirling.software.SPDF.gps.auditoria;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.ASN1String;
import org.bouncycastle.asn1.ASN1TaggedObject;
import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x500.style.IETFUtils;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cms.CMSException;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;

/**
 * Lê do PDF assinado o certificado que assinou por último.
 *
 * <p>É daqui, e não do status HTTP, que a trilha tira o sucesso: o {@code CertSignController.sign}
 * engole a exceção e responde 200 com arquivo vazio ou sem assinatura nova.
 */
final class AssinaturaDoPdf {

    /** ICP-Brasil, e-CPF: data de nascimento (8) + CPF (11) + ... */
    private static final String OID_DADOS_PESSOA_FISICA = "2.16.76.1.3.1";

    /** ICP-Brasil, e-CNPJ: CNPJ da empresa. */
    private static final String OID_CNPJ = "2.16.76.1.3.3";

    /** CN no formato ICP-Brasil: "NOME:12345678901" ou "RAZAO SOCIAL:12345678000190". */
    private static final Pattern DOCUMENTO_NO_CN = Pattern.compile(":(\\d{14}|\\d{11})$");

    private AssinaturaDoPdf() {}

    /**
     * O certificado da última assinatura, se ela for nova: cobre o arquivo até o fim e está depois
     * dos {@code tamanhoDaEntrada} bytes do PDF enviado, na parte que a gravação incremental
     * acrescentou. Vazio se o PDF não abrir, não tiver assinatura ou a última não for nova (por
     * exemplo, um PDF já assinado devolvido sem mudança).
     */
    static Optional<EventoDeAuditoria.Certificado> daUltimaAssinatura(
            Path pdf, long tamanhoDaEntrada) {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            PDSignature assinatura = doc.getLastSignatureDictionary();
            if (assinatura == null) {
                return Optional.empty();
            }
            int[] faixa = assinatura.getByteRange();
            if (faixa == null
                    || faixa.length != 4
                    || faixa[1] < tamanhoDaEntrada
                    || (long) faixa[2] + faixa[3] != Files.size(pdf)) {
                return Optional.empty();
            }
            byte[] conteudo;
            try (InputStream in = Files.newInputStream(pdf)) {
                conteudo = assinatura.getContents(in);
            }
            return Optional.of(doCms(conteudo));
        } catch (IOException | CMSException | RuntimeException e) {
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private static EventoDeAuditoria.Certificado doCms(byte[] cms) throws CMSException {
        CMSSignedData assinado = new CMSSignedData(cms);
        SignerInformation assinante = assinado.getSignerInfos().getSigners().iterator().next();
        Collection<X509CertificateHolder> candidatos =
                assinado.getCertificates().getMatches(assinante.getSID());
        return descrever(candidatos.iterator().next());
    }

    static EventoDeAuditoria.Certificado descrever(X509CertificateHolder cert) {
        String titular = cn(cert.getSubject());
        String emissor = cn(cert.getIssuer());
        byte[] der;
        try {
            der = cert.getEncoded();
        } catch (IOException e) {
            der = new byte[0];
        }
        return new EventoDeAuditoria.Certificado(
                titular,
                cpfCnpj(cert, titular),
                cert.getSerialNumber().toString(16).toUpperCase(),
                emissor != null ? emissor : cert.getIssuer().toString(),
                cert.getNotAfter().toInstant().toString(),
                TrilhaDeAuditoria.sha256(der),
                null);
    }

    /** CNPJ se houver (e-CNPJ), senão CPF (e-CPF), senão o número no fim do CN. */
    private static String cpfCnpj(X509CertificateHolder cert, String titular) {
        String cnpj = null;
        String cpf = null;
        Extension san = cert.getExtension(Extension.subjectAlternativeName);
        if (san != null) {
            for (GeneralName nome : GeneralNames.getInstance(san.getParsedValue()).getNames()) {
                if (nome.getTagNo() != GeneralName.otherName) {
                    continue;
                }
                ASN1Sequence outro = ASN1Sequence.getInstance(nome.getName());
                String oid = ASN1ObjectIdentifier.getInstance(outro.getObjectAt(0)).getId();
                String valor = texto(outro.getObjectAt(1));
                if (valor == null) {
                    continue;
                }
                if (OID_CNPJ.equals(oid) && valor.matches("\\d{14}")) {
                    cnpj = valor;
                } else if (OID_DADOS_PESSOA_FISICA.equals(oid) && valor.length() >= 19) {
                    String candidato = valor.substring(8, 19);
                    if (candidato.matches("\\d{11}") && !candidato.equals("0".repeat(11))) {
                        cpf = candidato;
                    }
                }
            }
        }
        if (cnpj != null) {
            return cnpj;
        }
        if (cpf != null) {
            return cpf;
        }
        if (titular != null) {
            Matcher m = DOCUMENTO_NO_CN.matcher(titular);
            if (m.find()) {
                return m.group(1);
            }
        }
        return null;
    }

    /** O valor de um otherName vem como [0] EXPLICIT OCTET STRING ou PrintableString/UTF8. */
    private static String texto(ASN1Encodable valor) {
        ASN1Encodable base = valor;
        if (base instanceof ASN1TaggedObject marcado) {
            base = marcado.getExplicitBaseObject();
        }
        if (base instanceof ASN1OctetString octetos) {
            return new String(octetos.getOctets(), StandardCharsets.ISO_8859_1).trim();
        }
        if (base instanceof ASN1String cadeia) {
            return cadeia.getString().trim();
        }
        return null;
    }

    private static String cn(X500Name nome) {
        RDN[] cns = nome.getRDNs(BCStyle.CN);
        return cns.length == 0 ? null : IETFUtils.valueToString(cns[0].getFirst().getValue());
    }
}
