package stirling.software.SPDF.gps.auditoria;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
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
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

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
 * atravessa os meses.
 *
 * <p>A cadeia não tem chave: ela acusa corrupção acidental e edição de uma linha no meio sem
 * refazer as seguintes, e {@link #verificar()} aponta onde. Não acusa quem recalcula a cadeia
 * inteira, nem edição ou remoção das últimas linhas (a cabeça é relida do disco no reinício). Para
 * isso cada gravação escreve no log da aplicação o hash da linha nova, fora do volume; a âncora
 * definitiva é a cópia fora da máquina (#20).
 *
 * <p>Uma gravação interrompida (kill, disco cheio) deixa uma linha incompleta. A gravação seguinte
 * começa numa linha nova e se encadeia à última linha completa, e {@link #verificar()} mostra a
 * incompleta como aviso, não como quebra. Linha cortada nunca é JSON válido; um evento inteiro sem
 * {@code hash_anterior} não é gravação interrompida, foi posto por fora, e quebra a cadeia.
 *
 * <p>Uma instância só grava por vez ({@code synchronized}); o Stirling roda em réplica única. A
 * leitura não trava a gravação: o arquivo só cresce, e a linha que estiver sendo escrita aparece
 * como incompleta.
 */
@Slf4j
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

    /**
     * Hash da última linha da cadeia; lido do disco quando o serviço sobe ou na primeira gravação.
     */
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

    /**
     * Escreve no log a cabeça da cadeia assim que o serviço sobe, e não só na primeira gravação:
     * editar a última linha e reiniciar aparece no log mesmo que ninguém assine depois.
     */
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void anunciarCabeca() {
        if (ultimoHash != null) {
            return;
        }
        try {
            ultimoHash = hashDaUltimaLinha();
            log.info("Trilha de auditoria: cabeça lida do disco, hash {}", ultimoHash);
        } catch (IOException e) {
            // A primeira gravação tenta de novo, e falha a assinatura se ainda não conseguir ler.
            log.error("Trilha de auditoria: não foi possível ler a cabeça da cadeia", e);
        }
    }

    /** Carimba id, data/hora e hash anterior, grava e devolve o evento como ficou no arquivo. */
    public synchronized EventoDeAuditoria registrar(EventoDeAuditoria rascunho) throws IOException {
        Files.createDirectories(diretorio);
        if (ultimoHash == null) {
            ultimoHash = hashDaUltimaLinha();
            log.info("Trilha de auditoria: cabeça lida do disco, hash {}", ultimoHash);
        }
        Instant agora = relogio.instant();
        EventoDeAuditoria evento =
                rascunho.carimbar(UUID.randomUUID().toString(), agora.toString(), ultimoHash);
        String linha = json.writeValueAsString(evento);
        Path arquivo = diretorio.resolve(PREFIXO + MES.format(agora) + ".jsonl");
        // Gravação anterior interrompida: a linha nova não pode emendar na incompleta.
        String quebra = terminaNoMeioDeUmaLinha(arquivo) ? "\n" : "";
        Files.writeString(
                arquivo,
                quebra + linha + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
                StandardOpenOption.SYNC);
        ultimoHash = sha256(linha);
        // Âncora fora do volume até a #20: quem edita o arquivo não edita o log do Railway.
        log.info(
                "Trilha de auditoria: evento {} ({}) gravado, hash {}",
                evento.id(),
                evento.resultado(),
                ultimoHash);
        return evento;
    }

    /** Eventos que passam no filtro, do mais recente para o mais antigo, até {@code limite}. */
    public List<EventoDeAuditoria> consultar(Predicate<EventoDeAuditoria> filtro, int limite)
            throws IOException {
        List<EventoDeAuditoria> encontrados = new ArrayList<>();
        List<Path> arquivos = arquivos();
        for (int i = arquivos.size() - 1; i >= 0 && encontrados.size() < limite; i--) {
            List<String> linhas = Files.readAllLines(arquivos.get(i), StandardCharsets.UTF_8);
            for (int j = linhas.size() - 1; j >= 0 && encontrados.size() < limite; j--) {
                if (linhas.get(j).isBlank()) {
                    continue;
                }
                EventoDeAuditoria evento = evento(linhas.get(j));
                // Linha corrompida, incompleta ou sem elo na cadeia: fica fora da consulta, e
                // verificar() acusa.
                if (evento != null && evento.hashAnterior() != null && filtro.test(evento)) {
                    encontrados.add(evento);
                }
            }
        }
        return encontrados;
    }

    /**
     * Refaz a cadeia de hash de todos os arquivos, na ordem em que foram gravados, lendo linha a
     * linha.
     *
     * <p>Linha que não é evento só vira aviso se a linha seguinte se encadear por cima dela, à
     * última linha completa: é o rastro de uma gravação interrompida, que nunca entrou na cadeia.
     * No fim da trilha, também é aviso (pode ser a gravação em andamento).
     */
    public Integridade verificar() throws IOException {
        String anterior = HASH_INICIAL;
        long eventos = 0;
        List<Posicao> incompletas = new ArrayList<>();
        List<String> avisos = new ArrayList<>();
        for (Path arquivo : arquivos()) {
            try (BufferedReader leitor = Files.newBufferedReader(arquivo, StandardCharsets.UTF_8)) {
                String linha;
                int numero = 0;
                while ((linha = leitor.readLine()) != null) {
                    numero++;
                    if (linha.isBlank()) {
                        continue;
                    }
                    EventoDeAuditoria evento = evento(linha);
                    if (evento == null) {
                        // Linha cortada nunca é JSON válido: hash_anterior é o último campo.
                        incompletas.add(new Posicao(arquivo, numero));
                        continue;
                    }
                    if (evento.hashAnterior() == null) {
                        // Evento inteiro sem elo: não sai de gravação interrompida, foi posto
                        // por fora. Não muda nenhuma linha existente, então só a cadeia o acusa.
                        return Integridade.quebrada(
                                eventos,
                                arquivo,
                                numero,
                                "evento sem hash_anterior (inserido por fora da aplicação)",
                                avisos);
                    }
                    if (!anterior.equals(evento.hashAnterior())) {
                        return incompletas.isEmpty()
                                ? Integridade.quebrada(
                                        eventos,
                                        arquivo,
                                        numero,
                                        "hash_anterior não confere com a linha anterior (editada,"
                                                + " apagada ou fora de ordem)",
                                        avisos)
                                : Integridade.quebrada(
                                        eventos,
                                        incompletas.get(0).arquivo(),
                                        incompletas.get(0).linha(),
                                        "linha não é um evento válido",
                                        avisos);
                    }
                    for (Posicao incompleta : incompletas) {
                        avisos.add(incompleta + ": gravação interrompida, linha fora da cadeia");
                    }
                    incompletas.clear();
                    anterior = sha256(linha);
                    eventos++;
                }
            }
        }
        for (Posicao incompleta : incompletas) {
            avisos.add(incompleta + ": linha incompleta no fim da trilha");
        }
        return new Integridade(true, eventos, null, null, null, avisos);
    }

    /** No mesmo formato das linhas do arquivo, para a consulta não mudar o nome dos campos. */
    String paraJson(Object valor) {
        return json.writeValueAsString(valor);
    }

    /** {@code avisos}: linhas incompletas de gravação interrompida, que não quebram a cadeia. */
    public record Integridade(
            boolean integra,
            long eventos,
            String arquivo,
            Integer linha,
            String detalhe,
            List<String> avisos) {

        static Integridade quebrada(
                long eventos, Path arquivo, int linha, String detalhe, List<String> avisos) {
            return new Integridade(
                    false, eventos, arquivo.getFileName().toString(), linha, detalhe, avisos);
        }
    }

    private record Posicao(Path arquivo, int linha) {
        @Override
        public String toString() {
            return arquivo.getFileName() + " linha " + linha;
        }
    }

    /** O evento da linha, ou nulo se ela não for um evento (corrompida ou incompleta). */
    private EventoDeAuditoria evento(String linha) {
        try {
            return json.readValue(linha, EventoDeAuditoria.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** O arquivo existe e não termina em quebra de linha: a última gravação foi interrompida. */
    private static boolean terminaNoMeioDeUmaLinha(Path arquivo) throws IOException {
        if (!Files.exists(arquivo)) {
            return false;
        }
        try (SeekableByteChannel canal = Files.newByteChannel(arquivo)) {
            if (canal.size() == 0) {
                return false;
            }
            ByteBuffer ultimo = ByteBuffer.allocate(1);
            canal.position(canal.size() - 1).read(ultimo);
            return ultimo.get(0) != '\n';
        }
    }

    private String hashDaUltimaLinha() throws IOException {
        List<Path> arquivos = arquivos();
        for (int i = arquivos.size() - 1; i >= 0; i--) {
            List<String> linhas = Files.readAllLines(arquivos.get(i), StandardCharsets.UTF_8);
            for (int j = linhas.size() - 1; j >= 0; j--) {
                // Mesmo critério do verificar(): pula a linha incompleta de uma gravação
                // interrompida e o evento sem elo, que nunca entraram na cadeia.
                EventoDeAuditoria evento = linhas.get(j).isBlank() ? null : evento(linhas.get(j));
                if (evento != null && evento.hashAnterior() != null) {
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
