package stirling.software.SPDF.gps.auditoria;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import stirling.software.common.configuration.InstallationPathConfig;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * Trilha de auditoria em JSONL, um arquivo por mês ({@code assinaturas-AAAA-MM.jsonl}).
 *
 * <p>Só acrescenta: não há código que edite ou apague linha. Cada linha leva em {@code
 * hash_anterior} o SHA-256 da linha anterior (a primeira de todas, 64 zeros), numa cadeia única que
 * atravessa os meses. Editar, apagar ou reordenar linha por fora quebra a cadeia, e {@link
 * #verificar()} aponta onde. Truncar o fim do último arquivo não deixa rastro: isso só a cópia fora
 * da máquina (alertas da #20) resolve.
 *
 * <p>Uma instância só grava por vez ({@code synchronized}); o Stirling roda em réplica única.
 */
@Service
public class TrilhaDeAuditoria {

    static final String HASH_INICIAL = "0".repeat(64);
    private static final String PREFIXO = "assinaturas-";
    private static final DateTimeFormatter MES =
            DateTimeFormatter.ofPattern("yyyy-MM").withZone(ZoneOffset.UTC);

    private final Path diretorio;
    private final Clock relogio;
    private final ObjectMapper json =
            JsonMapper.builder()
                    .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    // Jackson 3 ordena por nome por padrão; a linha sai na ordem do record.
                    .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                    .build();

    /** Hash da última linha gravada; lido do disco na primeira gravação. */
    private String ultimoHash;

    @Autowired
    public TrilhaDeAuditoria(@Value("${gps.auditoria.diretorio:}") String diretorio) {
        this(
                diretorio == null || diretorio.isBlank()
                        ? Path.of(InstallationPathConfig.getConfigPath(), "audit")
                        : Path.of(diretorio),
                Clock.systemUTC());
    }

    TrilhaDeAuditoria(Path diretorio, Clock relogio) {
        this.diretorio = diretorio;
        this.relogio = relogio;
    }

    /** Carimba id, data/hora e hash anterior, grava e devolve o evento como ficou no arquivo. */
    public synchronized EventoDeAuditoria registrar(EventoDeAuditoria rascunho) throws IOException {
        Files.createDirectories(diretorio);
        if (ultimoHash == null) {
            ultimoHash = hashDaUltimaLinha();
        }
        Instant agora = relogio.instant();
        EventoDeAuditoria evento =
                rascunho.carimbar(UUID.randomUUID().toString(), agora.toString(), ultimoHash);
        String linha = json.writeValueAsString(evento);
        Files.writeString(
                diretorio.resolve(PREFIXO + MES.format(agora) + ".jsonl"),
                linha + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
                StandardOpenOption.SYNC);
        ultimoHash = sha256(linha);
        return evento;
    }

    /** Eventos que passam no filtro, do mais recente para o mais antigo, até {@code limite}. */
    public synchronized List<EventoDeAuditoria> consultar(
            Predicate<EventoDeAuditoria> filtro, int limite) throws IOException {
        List<EventoDeAuditoria> encontrados = new ArrayList<>();
        List<Path> arquivos = arquivos();
        for (int i = arquivos.size() - 1; i >= 0 && encontrados.size() < limite; i--) {
            List<String> linhas = Files.readAllLines(arquivos.get(i), StandardCharsets.UTF_8);
            for (int j = linhas.size() - 1; j >= 0 && encontrados.size() < limite; j--) {
                if (linhas.get(j).isBlank()) {
                    continue;
                }
                EventoDeAuditoria evento;
                try {
                    evento = json.readValue(linhas.get(j), EventoDeAuditoria.class);
                } catch (RuntimeException e) {
                    // Linha corrompida: a consulta segue, e verificar() acusa.
                    continue;
                }
                if (filtro.test(evento)) {
                    encontrados.add(evento);
                }
            }
        }
        return encontrados;
    }

    /** Refaz a cadeia de hash de todos os arquivos, na ordem em que foram gravados. */
    public synchronized Integridade verificar() throws IOException {
        String anterior = HASH_INICIAL;
        long eventos = 0;
        for (Path arquivo : arquivos()) {
            List<String> linhas = Files.readAllLines(arquivo, StandardCharsets.UTF_8);
            for (int i = 0; i < linhas.size(); i++) {
                String linha = linhas.get(i);
                if (linha.isBlank()) {
                    continue;
                }
                String declarado;
                try {
                    declarado = json.readValue(linha, EventoDeAuditoria.class).hashAnterior();
                } catch (RuntimeException e) {
                    return Integridade.quebrada(
                            eventos, arquivo, i + 1, "linha não é um evento válido");
                }
                if (!anterior.equals(declarado)) {
                    return Integridade.quebrada(
                            eventos,
                            arquivo,
                            i + 1,
                            "hash_anterior não confere com a linha anterior (editada, apagada ou"
                                    + " fora de ordem)");
                }
                anterior = sha256(linha);
                eventos++;
            }
        }
        return new Integridade(true, eventos, null, null, null);
    }

    /** No mesmo formato das linhas do arquivo, para a consulta não mudar o nome dos campos. */
    String paraJson(Object valor) {
        return json.writeValueAsString(valor);
    }

    public record Integridade(
            boolean integra, long eventos, String arquivo, Integer linha, String detalhe) {

        static Integridade quebrada(long eventos, Path arquivo, int linha, String detalhe) {
            return new Integridade(
                    false, eventos, arquivo.getFileName().toString(), linha, detalhe);
        }
    }

    private String hashDaUltimaLinha() throws IOException {
        List<Path> arquivos = arquivos();
        for (int i = arquivos.size() - 1; i >= 0; i--) {
            List<String> linhas = Files.readAllLines(arquivos.get(i), StandardCharsets.UTF_8);
            for (int j = linhas.size() - 1; j >= 0; j--) {
                if (!linhas.get(j).isBlank()) {
                    return sha256(linhas.get(j));
                }
            }
        }
        return HASH_INICIAL;
    }

    /** Nome com AAAA-MM: a ordem alfabética é a cronológica. */
    private List<Path> arquivos() throws IOException {
        if (!Files.isDirectory(diretorio)) {
            return List.of();
        }
        try (Stream<Path> listagem = Files.list(diretorio)) {
            return listagem.filter(
                            p -> {
                                String nome = p.getFileName().toString();
                                return nome.startsWith(PREFIXO) && nome.endsWith(".jsonl");
                            })
                    .sorted()
                    .toList();
        }
    }

    static String sha256(String linha) {
        return sha256(linha.getBytes(StandardCharsets.UTF_8));
    }

    static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }

    static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM sem SHA-256", e);
        }
    }
}
