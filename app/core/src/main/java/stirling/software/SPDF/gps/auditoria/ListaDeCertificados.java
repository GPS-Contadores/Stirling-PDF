package stirling.software.SPDF.gps.auditoria;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

import stirling.software.common.configuration.InstallationPathConfig;

/**
 * Quem pode assinar com cada certificado ({@code configs/gps/certificados.yml}):
 *
 * <pre>
 * padrao: negar            # certificado fora da lista: negar | permitir
 * certificados:
 *   - descricao: GPS Contadores matriz
 *     cpf_cnpj: "11.222.333/0001-81"   # e/ou sha256 do certificado
 *     permitidos: [fulano@gestao.com.br, "papel:assinante"]
 * </pre>
 *
 * <p>Uma entrada casa quando todas as chaves que ela traz ({@code cpf_cnpj}, {@code sha256}) batem
 * com o certificado. Basta uma entrada que case permitir o usuário, por e-mail ou por papel.
 * Certificado que nenhuma entrada descreve, ou que não se consegue ler, segue o {@code padrao}.
 *
 * <p>O arquivo é relido quando muda (data e tamanho), sem reiniciar. Sem arquivo, vale o padrão de
 * {@code gps.certificados.padrao} ({@code GPS_CERTIFICADOS_PADRAO}, {@code negar} se vazio).
 * Arquivo que não se entende nega tudo: lista quebrada não pode liberar assinatura.
 */
@Slf4j
@Component
public class ListaDeCertificados {

    static final String NEGAR = "negar";
    static final String PERMITIR = "permitir";

    /** Resultado para a trilha: {@code motivo} vai para a linha {@code negado}. */
    record Decisao(boolean permitido, String motivo) {}

    private record Entrada(
            String descricao, String cpfCnpj, String sha256, Set<String> permitidos) {}

    private record Conteudo(boolean permitirForaDaLista, List<Entrada> entradas, String erro) {}

    private final Path arquivo;
    private final boolean permitirSemArquivo;

    private FileTime lidoEm;
    private long lidoComTamanho = -1;
    private Conteudo conteudo;

    @Autowired
    public ListaDeCertificados(
            @Value("${gps.certificados.arquivo:}") String arquivo,
            @Value("${gps.certificados.padrao:" + NEGAR + "}") String padraoSemArquivo) {
        this(
                arquivo == null || arquivo.isBlank()
                        ? Path.of(InstallationPathConfig.getConfigPath(), "gps", "certificados.yml")
                        : Path.of(arquivo),
                padraoSemArquivo);
    }

    ListaDeCertificados(Path arquivo, String padraoSemArquivo) {
        this.arquivo = arquivo;
        this.permitirSemArquivo = PERMITIR.equalsIgnoreCase(Objects.toString(padraoSemArquivo, ""));
        if (!Files.exists(arquivo)) {
            log.warn(
                    "Lista de certificados {} não existe: todo certificado segue o padrão {}",
                    arquivo,
                    permitirSemArquivo ? PERMITIR : NEGAR);
        }
    }

    /**
     * @param certificado o certificado do pedido, ou nulo se não foi possível identificá-lo
     */
    public Decisao decidir(
            EventoDeAuditoria.Certificado certificado,
            IdentidadeDoProxy identidade,
            Set<PapeisDoUsuario.Papel> papeis) {
        Conteudo lista = conteudo();
        if (lista.erro() != null) {
            return new Decisao(false, "lista de certificados inválida: " + lista.erro());
        }
        if (certificado == null) {
            return lista.permitirForaDaLista()
                    ? new Decisao(true, null)
                    : new Decisao(false, "certificado não identificado (padrão negar)");
        }
        List<Entrada> casam =
                lista.entradas().stream().filter(e -> descreve(e, certificado)).toList();
        if (casam.isEmpty()) {
            return lista.permitirForaDaLista()
                    ? new Decisao(true, null)
                    : new Decisao(false, "certificado fora da lista (padrão negar)");
        }
        String email = normalizar(identidade.email());
        for (Entrada entrada : casam) {
            if (entrada.permitidos().contains(email)) {
                return new Decisao(true, null);
            }
            for (PapeisDoUsuario.Papel papel : papeis) {
                if (entrada.permitidos().contains("papel:" + papel.nome())) {
                    return new Decisao(true, null);
                }
            }
        }
        return new Decisao(
                false,
                "sem permissão para o certificado "
                        + casam.stream()
                                .map(e -> e.descricao() != null ? e.descricao() : "sem descrição")
                                .toList());
    }

    private static boolean descreve(Entrada entrada, EventoDeAuditoria.Certificado cert) {
        boolean algumaChave = false;
        if (entrada.cpfCnpj() != null) {
            algumaChave = true;
            if (!entrada.cpfCnpj().equals(cert.cpfCnpj())) {
                return false;
            }
        }
        if (entrada.sha256() != null) {
            algumaChave = true;
            if (!entrada.sha256().equalsIgnoreCase(Objects.toString(cert.sha256(), ""))) {
                return false;
            }
        }
        return algumaChave;
    }

    /** Relê quando o arquivo muda; entre uma leitura e outra, usa a última. */
    private synchronized Conteudo conteudo() {
        try {
            if (!Files.exists(arquivo)) {
                lidoEm = null;
                lidoComTamanho = -1;
                conteudo = new Conteudo(permitirSemArquivo, List.of(), null);
                return conteudo;
            }
            FileTime modificado = Files.getLastModifiedTime(arquivo);
            long tamanho = Files.size(arquivo);
            if (conteudo == null || !modificado.equals(lidoEm) || tamanho != lidoComTamanho) {
                conteudo = ler(Files.readString(arquivo, StandardCharsets.UTF_8));
                lidoEm = modificado;
                lidoComTamanho = tamanho;
                if (conteudo.erro() != null) {
                    log.error(
                            "Lista de certificados {} inválida, toda assinatura negada: {}",
                            arquivo,
                            conteudo.erro());
                } else {
                    log.info(
                            "Lista de certificados {} lida: {} entradas, padrão {}",
                            arquivo,
                            conteudo.entradas().size(),
                            conteudo.permitirForaDaLista() ? PERMITIR : NEGAR);
                }
            }
            return conteudo;
        } catch (IOException e) {
            return new Conteudo(false, List.of(), "não foi possível ler: " + e.getMessage());
        }
    }

    static Conteudo ler(String yaml) {
        Object raiz;
        try {
            raiz = new Load(LoadSettings.builder().build()).loadFromString(yaml);
        } catch (RuntimeException e) {
            return new Conteudo(false, List.of(), "YAML inválido: " + e.getMessage());
        }
        if (raiz == null) {
            return new Conteudo(false, List.of(), null);
        }
        if (!(raiz instanceof Map<?, ?> mapa)) {
            return new Conteudo(false, List.of(), "a raiz tem de ter padrao e certificados");
        }
        String padrao = normalizar(Objects.toString(mapa.get("padrao"), NEGAR));
        if (!padrao.equals(NEGAR) && !padrao.equals(PERMITIR)) {
            return new Conteudo(false, List.of(), "padrao tem de ser negar ou permitir");
        }
        List<Entrada> entradas = new ArrayList<>();
        Object lista = mapa.get("certificados");
        if (lista != null && !(lista instanceof List<?>)) {
            return new Conteudo(false, List.of(), "certificados tem de ser uma lista");
        }
        int i = 0;
        for (Object item : lista == null ? List.of() : (List<?>) lista) {
            i++;
            if (!(item instanceof Map<?, ?> entrada)) {
                return new Conteudo(false, List.of(), "certificado " + i + " não é um mapa");
            }
            for (String chave : List.of("cpf_cnpj", "sha256")) {
                if (entrada.get(chave) != null && !(entrada.get(chave) instanceof String)) {
                    // Sem aspas, 11222333000181 vira inteiro e um hash como 12e45 vira número.
                    // Só aceitar texto evita depender do schema do YAML (no core do YAML 1.2,
                    // até 01234567000189 perderia o zero da frente).
                    return new Conteudo(
                            false,
                            List.of(),
                            chave + " do certificado " + i + " tem de estar entre aspas");
                }
            }
            String cpfCnpj = digitos(entrada.get("cpf_cnpj"));
            String sha256 = texto(entrada.get("sha256"));
            if (cpfCnpj == null && sha256 == null) {
                return new Conteudo(
                        false, List.of(), "certificado " + i + " sem cpf_cnpj nem sha256");
            }
            Object permitidos = entrada.get("permitidos");
            if (permitidos != null && !(permitidos instanceof List<?>)) {
                return new Conteudo(
                        false, List.of(), "permitidos do certificado " + i + " tem de ser lista");
            }
            Set<String> quem =
                    permitidos == null
                            ? Set.of()
                            : ((List<?>) permitidos)
                                    .stream()
                                            .map(p -> normalizar(Objects.toString(p, "")))
                                            .filter(p -> !p.isEmpty())
                                            .collect(java.util.stream.Collectors.toSet());
            entradas.add(new Entrada(texto(entrada.get("descricao")), cpfCnpj, sha256, quem));
        }
        return new Conteudo(padrao.equals(PERMITIR), List.copyOf(entradas), null);
    }

    private static String texto(Object valor) {
        String s = valor == null ? null : valor.toString().trim();
        return s == null || s.isEmpty() ? null : s;
    }

    private static String digitos(Object valor) {
        String s = valor == null ? "" : valor.toString().replaceAll("\\D", "");
        return s.isEmpty() ? null : s;
    }

    private static String normalizar(String valor) {
        return valor == null ? "" : valor.trim().toLowerCase(Locale.ROOT);
    }
}
