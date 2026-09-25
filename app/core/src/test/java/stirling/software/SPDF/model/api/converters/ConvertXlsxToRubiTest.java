package stirling.software.SPDF.model.api.converters;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import com.sun.net.httpserver.HttpServer;

/**
 * The controller against a stand-in for {@code POST /folha/api/converter} of the ofx service,
 * answering what the real one answers (see ofx-service/tests/test_api_folha.py in
 * GPS-Contadores/conversor-documentos).
 */
class ConvertXlsxToRubiTest {

    private static final byte[] TXT =
            "0101501000000001000010010115000000000010000000050000000000000I\r\n"
                    .getBytes(StandardCharsets.US_ASCII);

    private HttpServer server;
    private final AtomicReference<String> receivedBody = new AtomicReference<>();
    private final AtomicReference<String> receivedPath = new AtomicReference<>();
    private int status;
    private byte[] responseBody;
    private Map<String, String> responseHeaders;
    private ConvertXlsxToRubi controller;

    @BeforeEach
    void startService() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    receivedPath.set(exchange.getRequestURI().getPath());
                    receivedBody.set(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.ISO_8859_1));
                    responseHeaders.forEach(exchange.getResponseHeaders()::set);
                    exchange.sendResponseHeaders(status, responseBody.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(responseBody);
                    }
                });
        server.start();
        controller = new ConvertXlsxToRubi();
        ReflectionTestUtils.setField(
                controller,
                "serviceUrl",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    @AfterEach
    void stopService() {
        server.stop(0);
    }

    private static ConvertXlsxToRubiRequest request(String calculo) {
        ConvertXlsxToRubiRequest request = new ConvertXlsxToRubiRequest();
        request.setFileInput(
                new MockMultipartFile(
                        "fileInput",
                        "Folha ACME.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        new byte[] {'P', 'K', 3, 4}));
        request.setCalculo(calculo);
        return request;
    }

    private void serviceAnswers(int status, String body, Map<String, String> headers) {
        serviceAnswers(status, body.getBytes(StandardCharsets.UTF_8), headers);
    }

    private void serviceAnswers(int status, byte[] body, Map<String, String> headers) {
        this.status = status;
        this.responseBody = body;
        this.responseHeaders = headers;
    }

    @Test
    void txtComesBackWithTheServiceFileNameAndTheRelayedHeaders() throws Exception {
        serviceAnswers(
                200,
                TXT,
                Map.of(
                        "Content-Type", "text/plain; charset=windows-1252",
                        "Content-Disposition",
                                "attachment; filename=\"FP_EVENTOS_0150_202607_fixo.txt\"",
                        "X-GPS-Lancamentos", "7",
                        "X-GPS-Arquivos", "1",
                        "X-GPS-Layout", "novo",
                        "X-GPS-Avisos", "WyJjb2x1bmEgQyJd"));

        ResponseEntity<byte[]> response = controller.processXlsxToRubi(request(" 505 "));

        assertEquals("/folha/api/converter", receivedPath.get());
        assertTrue(receivedBody.get().contains("name=\"arquivo\"; filename=\"Folha ACME.xlsx\""));
        assertTrue(receivedBody.get().contains("name=\"calculo\"\r\n\r\n505\r\n"));
        assertArrayEquals(TXT, response.getBody());
        assertEquals("text", response.getHeaders().getContentType().getType());
        assertEquals(
                "FP_EVENTOS_0150_202607_fixo.txt",
                response.getHeaders().getContentDisposition().getFilename());
        assertEquals("7", response.getHeaders().getFirst("X-GPS-Lancamentos"));
        assertEquals("1", response.getHeaders().getFirst("X-GPS-Arquivos"));
        assertEquals("novo", response.getHeaders().getFirst("X-GPS-Layout"));
        assertEquals("WyJjb2x1bmEgQyJd", response.getHeaders().getFirst("X-GPS-Avisos"));
    }

    @Test
    void blankCalculoIsNotSent() throws Exception {
        serviceAnswers(200, TXT, Map.of("Content-Type", "text/plain; charset=windows-1252"));

        controller.processXlsxToRubi(request("  "));

        assertFalse(receivedBody.get().contains("name=\"calculo\""));
    }

    @Test
    void zipForSeveralCompaniesKeepsItsTypeAndName() throws Exception {
        byte[] zip = {'P', 'K', 5, 6};
        serviceAnswers(
                200,
                zip,
                Map.of(
                        "Content-Type", "application/zip",
                        "Content-Disposition", "attachment; filename=\"FP_EVENTOS_202607.zip\"",
                        "X-GPS-Arquivos", "2"));

        ResponseEntity<byte[]> response = controller.processXlsxToRubi(request(null));

        assertArrayEquals(zip, response.getBody());
        assertEquals("application/zip", response.getHeaders().getContentType().toString());
        assertEquals(
                "FP_EVENTOS_202607.zip",
                response.getHeaders().getContentDisposition().getFilename());
    }

    @Test
    void headersOutsideTheExpectedShapeAreDropped() throws Exception {
        serviceAnswers(
                200,
                TXT,
                Map.of(
                        "Content-Type", "text/plain",
                        "X-GPS-Layout", "<script>",
                        "X-GPS-Lancamentos", "sete"));

        ResponseEntity<byte[]> response = controller.processXlsxToRubi(request(null));

        assertNull(response.getHeaders().getFirst("X-GPS-Layout"));
        assertNull(response.getHeaders().getFirst("X-GPS-Lancamentos"));
    }

    @Test
    void unexpectedFileNameFallsBackToTheSheetName() throws Exception {
        serviceAnswers(
                200,
                TXT,
                Map.of(
                        "Content-Type", "text/plain",
                        "Content-Disposition", "attachment; filename=\"../../etc/passwd\""));

        ResponseEntity<byte[]> response = controller.processXlsxToRubi(request(null));

        // WebResponseUtils URL-encodes the name, as for every other download.
        assertEquals(
                "Folha%20ACME.txt", response.getHeaders().getContentDisposition().getFilename());
    }

    @Test
    void cellRefusalBecomesOneLineWithEveryCell() {
        serviceAnswers(
                422,
                "{\"ok\":false,\"erro\":\"2 celula(s) com conteudo invalido — nenhum TXT foi"
                        + " gerado:\\n    - C13: numero 8 sem formato de hora\\n    - D14: texto"
                        + " 'x'\",\"dados\":{\"planilha_invalida\":true,\"problemas\":[\"C13: numero"
                        + " 8 sem formato de hora\",\"D14: texto 'x'\"]}}",
                Map.of("Content-Type", "application/json"));

        IllegalArgumentException error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> controller.processXlsxToRubi(request(null)));

        assertEquals(
                "TXT do RUBI não gerado: 2 celula(s) com conteudo invalido — nenhum TXT foi gerado:"
                        + " C13: numero 8 sem formato de hora | D14: texto 'x'",
                error.getMessage());
    }

    @Test
    void singleLineRefusalIsKeptAsIs() {
        serviceAnswers(
                422,
                "{\"ok\":false,\"erro\":\"A planilha não tem nenhum lançamento preenchido — nenhum"
                        + " TXT foi gerado.\",\"dados\":{\"sem_lancamentos\":true}}",
                Map.of("Content-Type", "application/json"));

        IllegalArgumentException error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> controller.processXlsxToRubi(request(null)));

        assertEquals(
                "TXT do RUBI não gerado: A planilha não tem nenhum lançamento preenchido — nenhum"
                        + " TXT foi gerado.",
                error.getMessage());
    }

    @Test
    void serviceFailureIsNotBlamedOnTheSheet() {
        serviceAnswers(500, "Internal Server Error", Map.of());

        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () -> controller.processXlsxToRubi(request(null)));

        assertEquals("O conversor da folha falhou (HTTP 500).", error.getMessage());
    }

    @Test
    void nonNumericCalculoIsRefusedBeforeCallingTheService() {
        serviceAnswers(200, TXT, Map.of());

        assertThrows(
                IllegalArgumentException.class,
                () -> controller.processXlsxToRubi(request("505a")));
        assertNull(receivedPath.get());
    }
}
