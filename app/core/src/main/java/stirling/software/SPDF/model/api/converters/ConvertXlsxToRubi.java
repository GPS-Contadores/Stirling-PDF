package stirling.software.SPDF.model.api.converters;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.multipart.MultipartFile;

import io.github.pixee.security.Filenames;
import io.swagger.v3.oas.annotations.Operation;

import lombok.extern.slf4j.Slf4j;

import stirling.software.common.annotations.AutoJobPostMapping;
import stirling.software.common.annotations.api.ConvertApi;
import stirling.software.common.enumeration.ResourceWeight;
import stirling.software.common.util.WebResponseUtils;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Payroll spreadsheet (xlsx) to the FP-EVENTOS TXT that Sênior/RUBI imports.
 *
 * <p>GPS-Contadores: the conversion is done by {@code POST /folha/api/converter} of the {@code ofx}
 * service (ofx-service/ in GPS-Contadores/conversor-documentos), the same container that serves PDF
 * → OFX. It holds the rules of the payroll department's generator (layout detection, strict hour
 * reading, one TXT per company) and refuses the sheet pointing at the cell to fix. This controller
 * only forwards the sheet and relays the result, so none of that logic is duplicated here.
 *
 * <p>The service answers with one TXT, or a zip with one TXT per company when a multi-block sheet
 * has more than one CNPJ.
 */
@Slf4j
@ConvertApi
public class ConvertXlsxToRubi {

    private static final Duration TIMEOUT = Duration.ofSeconds(120);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    // RUBI calculation codes are numeric: 4 digits in the csv layout, 5 in the fixed one.
    private static final Pattern CALCULO = Pattern.compile("\\d{1,5}");

    // Headers the service sends with a generated TXT, relayed to the Convert tool so it can show
    // how many entries and files came out and the warnings (e.g. an hour column without [h]:mm).
    // Same shapes as ofx-service/tests/test_api_folha.py: anything else is dropped, so nothing
    // unexpected reaches the browser through this proxy. X-GPS-Avisos is base64(JSON array).
    private static final Map<String, Pattern> RELAYED_HEADERS =
            Map.of(
                    "X-GPS-Lancamentos", Pattern.compile("\\d{1,9}"),
                    "X-GPS-Arquivos", Pattern.compile("\\d{1,4}"),
                    "X-GPS-Layout", Pattern.compile("novo|multibloco|legado"),
                    "X-GPS-Avisos", Pattern.compile("[A-Za-z0-9+/=]+"));

    // FP_EVENTOS_0150_202607_fixo.txt, FP_EVENTOS_202607.zip: the service names the file, and the
    // name is what the payroll team matches against the company in RUBI.
    private static final Pattern SERVICE_FILE_NAME =
            Pattern.compile("filename=\"([A-Za-z0-9_.-]{1,100}\\.(?:txt|zip))\"");

    // HTTP/1.1 on purpose, as in ConvertPDFToOfx: over plain http the JDK client's HTTP/2
    // "Upgrade: h2c" makes uvicorn drop the multipart body.
    private final HttpClient httpClient =
            HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

    // Same service as PDF → OFX, so the same setting (env var PDFTOOFX_URL): one container, one
    // address to configure on Railway.
    @Value("${pdfToOfx.url:http://ofx:8000}")
    private String serviceUrl;

    @AutoJobPostMapping(
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            value = "/xlsx/rubi",
            resourceWeight = ResourceWeight.SMALL_WEIGHT)
    @Operation(
            summary = "Convert a payroll spreadsheet to the Sênior/RUBI import TXT",
            description =
                    "Reads the payroll spreadsheet filled in by the client and returns the"
                            + " FP-EVENTOS TXT that Sênior/RUBI imports (a zip with one TXT per"
                            + " company when the sheet has several). Nothing is generated if any"
                            + " cell is ambiguous. Input:XLSX Output:TXT Type:SISO")
    public ResponseEntity<byte[]> processXlsxToRubi(
            @ModelAttribute ConvertXlsxToRubiRequest request) throws Exception {
        MultipartFile inputFile = request.getFileInput();
        if (inputFile == null || inputFile.isEmpty()) {
            throw new IllegalArgumentException("Nenhuma planilha enviada.");
        }
        String calculo = request.getCalculo() == null ? "" : request.getCalculo().trim();
        if (!calculo.isEmpty() && !CALCULO.matcher(calculo).matches()) {
            throw new IllegalArgumentException(
                    "O código do cálculo tem só dígitos (até 5), recebi \"" + calculo + "\".");
        }

        String originalName = Filenames.toSimpleFileName(inputFile.getOriginalFilename());
        if (originalName == null || originalName.isBlank()) {
            originalName = "folha.xlsx";
        }

        String boundary = "----StirlingXlsx2Rubi" + UUID.randomUUID().toString().replace("-", "");
        byte[] body = multipart(boundary, originalName, inputFile.getBytes(), calculo);

        URI endpoint = URI.create(stripTrailingSlash(serviceUrl) + "/folha/api/converter");
        HttpRequest httpRequest =
                HttpRequest.newBuilder(endpoint)
                        .timeout(TIMEOUT)
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build();

        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            log.warn("payroll service unreachable at {}: {}", serviceUrl, e.toString());
            throw new IllegalStateException(
                    "O conversor da folha não respondeu. O serviço ofx está no ar?");
        }

        int status = response.statusCode();
        if (status == 200) {
            boolean zip =
                    response.headers()
                            .firstValue("Content-Type")
                            .map(type -> type.startsWith("application/zip"))
                            .orElse(false);
            ResponseEntity<byte[]> file =
                    WebResponseUtils.bytesToWebResponse(
                            response.body(),
                            fileName(response, originalName, zip),
                            zip
                                    ? MediaType.valueOf("application/zip")
                                    : MediaType.valueOf("text/plain; charset=windows-1252"));
            HttpHeaders headers = new HttpHeaders();
            headers.putAll(file.getHeaders());
            RELAYED_HEADERS.forEach(
                    (name, shape) ->
                            response.headers()
                                    .firstValue(name)
                                    .filter(value -> shape.matcher(value).matches())
                                    .ifPresent(value -> headers.set(name, value)));
            return ResponseEntity.status(file.getStatusCode())
                    .headers(headers)
                    .body(file.getBody());
        }
        if (status == 422 || status == 413) {
            // The spreadsheet itself is the problem. IllegalArgumentException because
            // JobExecutorService only lets that through as a 400 whose "detail" the frontend
            // shows; anything else becomes a generic 500.
            throw new IllegalArgumentException(
                    "TXT do RUBI não gerado: " + reason(response.body()));
        }
        log.warn("payroll service returned HTTP {}", status);
        throw new IllegalStateException("O conversor da folha falhou (HTTP " + status + ").");
    }

    /** The name the service chose, or the sheet's own name with the right extension. */
    static String fileName(HttpResponse<byte[]> response, String originalName, boolean zip) {
        String disposition = response.headers().firstValue("Content-Disposition").orElse("");
        Matcher matcher = SERVICE_FILE_NAME.matcher(disposition);
        if (matcher.find()) {
            return matcher.group(1);
        }
        String baseName =
                originalName.contains(".")
                        ? originalName.substring(0, originalName.lastIndexOf('.'))
                        : originalName;
        return baseName + (zip ? ".zip" : ".txt");
    }

    /**
     * The service answers 422 with {"erro": "...", "dados": {"problemas": ["C13: ...", ...]}}. The
     * "erro" of a cell-level refusal is a header line plus one indented line per cell, and the
     * frontend shows it in a single line, so it is rebuilt as "header C13: ... | D14: ...". 413 and
     * request validation errors come as FastAPI's {"detail": ...}.
     */
    static String reason(byte[] body) {
        String text = new String(body, StandardCharsets.UTF_8).trim();
        try {
            JsonNode json = JSON.readTree(text);
            JsonNode erro = json.get("erro");
            if (erro != null && erro.isTextual() && !erro.asText().isBlank()) {
                String message = erro.asText().trim();
                List<String> problems = new ArrayList<>();
                JsonNode listed = json.path("dados").path("problemas");
                if (listed.isArray()) {
                    listed.forEach(
                            item -> {
                                if (item.isTextual() && !item.asText().isBlank()) {
                                    problems.add(item.asText().trim());
                                }
                            });
                }
                if (message.contains("\n") && !problems.isEmpty()) {
                    return message.substring(0, message.indexOf('\n')).trim()
                            + " "
                            + String.join(" | ", problems);
                }
                return message;
            }
            JsonNode detail = json.get("detail");
            if (detail != null && detail.isTextual() && !detail.asText().isBlank()) {
                return detail.asText();
            }
            if (detail != null && detail.isArray() && !detail.isEmpty()) {
                List<String> messages = new ArrayList<>();
                detail.forEach(
                        item -> {
                            JsonNode msg = item.get("msg");
                            if (msg != null && msg.isTextual()) {
                                messages.add(msg.asText());
                            }
                        });
                if (!messages.isEmpty()) {
                    return "requisição inválida para o conversor da folha ("
                            + String.join("; ", messages)
                            + ").";
                }
            }
        } catch (JacksonException e) {
            // Not JSON: fall back to the raw text below.
        }
        return text.isEmpty() ? "planilha recusada pelo conversor." : text;
    }

    /** Multipart body for the service: the sheet as "arquivo", plus "calculo" when informed. */
    private static byte[] multipart(String boundary, String fileName, byte[] xlsx, String calculo)
            throws IOException {
        String safeName = fileName.replace("\"", "").replace("\r", "").replace("\n", "");
        StringBuilder tail = new StringBuilder("\r\n");
        if (!calculo.isEmpty()) {
            tail.append("--")
                    .append(boundary)
                    .append("\r\n")
                    .append("Content-Disposition: form-data; name=\"calculo\"\r\n\r\n")
                    .append(calculo)
                    .append("\r\n");
        }
        tail.append("--").append(boundary).append("--\r\n");
        String head =
                "--"
                        + boundary
                        + "\r\n"
                        + "Content-Disposition: form-data; name=\"arquivo\"; filename=\""
                        + safeName
                        + "\"\r\n"
                        + "Content-Type: "
                        + XLSX_MIME
                        + "\r\n\r\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream(xlsx.length + 512);
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(xlsx);
        out.write(tail.toString().getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
