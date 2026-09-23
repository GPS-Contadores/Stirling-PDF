package stirling.software.SPDF.model.api.converters;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
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
import stirling.software.common.model.api.PDFFile;
import stirling.software.common.util.WebResponseUtils;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * PDF bank statement / credit card bill to OFX.
 *
 * <p>GPS-Contadores: the conversion is done by the {@code ofx} service (ofx-service/ in
 * GPS-Contadores/conversor-documentos), running next to Stirling on the private network. It picks
 * the bank layout parser, checks that the transactions add up to the printed balance, and writes
 * the OFX 1.0.2 dialect that Questor imports. This controller only forwards the PDF and relays the
 * result, so none of that logic is duplicated here.
 *
 * <p>No token: the service is not public and login happens at the edge (oauth2-proxy).
 */
@Slf4j
@ConvertApi
public class ConvertPDFToOfx {

    private static final Duration TIMEOUT = Duration.ofSeconds(180);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String WARNINGS_HEADER = "X-GPS-Avisos";

    // HTTP/1.1 on purpose: the JDK client defaults to HTTP/2 and, over plain http, sends an
    // "Upgrade: h2c" request. The ofx service runs on uvicorn, which rejects the upgrade
    // ("Unsupported upgrade request") and loses the multipart body, answering 422
    // "arquivo: Field required".
    private final HttpClient httpClient =
            HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

    // Env var PDFTOOFX_URL (Spring relaxed binding) overrides it. The default is the service
    // name in docker-compose; on Railway it is http://ofx.railway.internal:8000.
    @Value("${pdfToOfx.url:http://ofx:8000}")
    private String serviceUrl;

    @AutoJobPostMapping(
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            value = "/pdf/ofx",
            resourceWeight = ResourceWeight.SMALL_WEIGHT)
    @Operation(
            summary = "Convert a PDF bank statement or credit card bill to OFX",
            description =
                    "Reads a text-based bank statement or card bill PDF and returns an OFX file"
                            + " for Questor. The OFX is only produced if the transactions add up"
                            + " to the balance printed on the document. Input:PDF Output:OFX"
                            + " Type:SISO")
    public ResponseEntity<byte[]> processPdfToOfx(@ModelAttribute PDFFile file) throws Exception {
        MultipartFile inputFile = file.getFileInput();
        if (inputFile == null || inputFile.isEmpty()) {
            throw new IllegalArgumentException("Nenhum PDF enviado.");
        }

        String originalName = Filenames.toSimpleFileName(inputFile.getOriginalFilename());
        if (originalName == null || originalName.isBlank()) {
            originalName = "extrato.pdf";
        }
        String baseName =
                originalName.contains(".")
                        ? originalName.substring(0, originalName.lastIndexOf('.'))
                        : originalName;

        String boundary = "----StirlingPdf2Ofx" + UUID.randomUUID().toString().replace("-", "");
        // exigir_conferencia=true: a balance mismatch is refused, not downgraded to a warning.
        // Warnings are relayed to the UI (see X-GPS-Avisos below), but a toast is easy to miss,
        // and an OFX that does not add up must never reach Questor looking like a normal file.
        byte[] body = multipart(boundary, originalName, inputFile.getBytes());

        URI endpoint = URI.create(stripTrailingSlash(serviceUrl) + "/ofx/api/converter");
        HttpRequest.Builder request =
                HttpRequest.newBuilder(endpoint)
                        .timeout(TIMEOUT)
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body));

        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            log.warn("ofx service unreachable at {}: {}", serviceUrl, e.toString());
            throw serviceFailure("O conversor OFX não respondeu. O serviço ofx está no ar?");
        }

        int status = response.statusCode();
        if (status == 200) {
            ResponseEntity<byte[]> ofx =
                    WebResponseUtils.bytesToWebResponse(
                            response.body(),
                            baseName + ".ofx",
                            MediaType.valueOf("application/x-ofx"));
            // The ofx service sends its warnings (e.g. "bank not identified in the header") as
            // base64(JSON array) in X-GPS-Avisos. Relay it so the Convert tool can show them;
            // dropping it would hand the user an OFX with a warning nobody ever sees.
            String warnings = response.headers().firstValue(WARNINGS_HEADER).orElse("");
            if (!warnings.isBlank() && warnings.matches("[A-Za-z0-9+/=]+")) {
                return ResponseEntity.status(ofx.getStatusCode())
                        .headers(ofx.getHeaders())
                        .header(WARNINGS_HEADER, warnings)
                        .body(ofx.getBody());
            }
            return ofx;
        }
        if (status == 422 || status == 413) {
            // The document itself is the problem (unknown layout, scanned PDF, password,
            // balance mismatch, too large). IllegalArgumentException because JobExecutorService
            // only lets that through; anything else becomes a generic 500. The global handler
            // turns it into a 400 ProblemDetail whose "detail" the frontend shows.
            throw new IllegalArgumentException("OFX não gerado: " + reason(response.body()));
        }
        log.warn("ofx service returned HTTP {}", status);
        throw serviceFailure("O conversor OFX falhou (HTTP " + status + ").");
    }

    /**
     * A plain RuntimeException on purpose. JobExecutorService turns it into a 500 with body
     * {"error": "Job failed: <message>"}, which the frontend shows; a ResponseStatusException would
     * show up as 'Job failed: 502 BAD_GATEWAY "..."', status and quotes included.
     */
    private static RuntimeException serviceFailure(String message) {
        return new IllegalStateException(message);
    }

    /**
     * The ofx service answers 422 with {"erro": "..."}, 413 with FastAPI's {"detail": "..."} and a
     * request validation error with {"detail": [{"msg": "...", "loc": [...]}, ...]}.
     */
    private static String reason(byte[] body) {
        String text = new String(body, StandardCharsets.UTF_8).trim();
        try {
            JsonNode json = JSON.readTree(text);
            for (String field : new String[] {"erro", "detail"}) {
                JsonNode value = json.get(field);
                if (value != null && value.isTextual() && !value.asText().isBlank()) {
                    return value.asText();
                }
            }
            JsonNode detail = json.get("detail");
            if (detail != null && detail.isArray() && !detail.isEmpty()) {
                StringBuilder messages = new StringBuilder();
                for (JsonNode item : detail) {
                    JsonNode msg = item.get("msg");
                    if (msg != null && msg.isTextual()) {
                        if (messages.length() > 0) {
                            messages.append("; ");
                        }
                        messages.append(msg.asText());
                    }
                }
                if (messages.length() > 0) {
                    return "requisição inválida para o conversor OFX (" + messages + ").";
                }
            }
        } catch (JacksonException e) {
            // Not JSON: fall back to the raw text below.
        }
        return text.isEmpty() ? "documento recusado pelo conversor." : text;
    }

    /** Multipart body for the ofx service: the PDF as "arquivo" plus exigir_conferencia=true. */
    private static byte[] multipart(String boundary, String fileName, byte[] pdf)
            throws IOException {
        String safeName = fileName.replace("\"", "").replace("\r", "").replace("\n", "");
        String head =
                "--"
                        + boundary
                        + "\r\n"
                        + "Content-Disposition: form-data; name=\"arquivo\"; filename=\""
                        + safeName
                        + "\"\r\n"
                        + "Content-Type: application/pdf\r\n\r\n";
        String tail =
                "\r\n--"
                        + boundary
                        + "\r\n"
                        + "Content-Disposition: form-data; name=\"exigir_conferencia\"\r\n\r\n"
                        + "true\r\n"
                        + "--"
                        + boundary
                        + "--\r\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream(pdf.length + 512);
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(pdf);
        out.write(tail.getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
