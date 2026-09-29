package stirling.software.SPDF.model.api.converters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import com.sun.net.httpserver.HttpServer;

/**
 * The controller against a stand-in for {@code POST /ofx/api/converter} of the ofx service,
 * answering what the real one answers (see ofx-service/tests/test_contrato_stirling.py in
 * GPS-Contadores/conversor-documentos).
 */
class ConvertPDFToOfxTest {

    private static final byte[] OFX = "OFXHEADER:100\r\n".getBytes(StandardCharsets.US_ASCII);

    private HttpServer server;
    private final AtomicReference<String> receivedBody = new AtomicReference<>();
    private final AtomicReference<String> receivedPath = new AtomicReference<>();
    private int status;
    private byte[] responseBody;
    private ConvertPDFToOfx controller;

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
                    exchange.getResponseHeaders()
                            .set(
                                    "Content-Type",
                                    status == 200 ? "application/x-ofx" : "application/json");
                    exchange.sendResponseHeaders(status, responseBody.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(responseBody);
                    }
                });
        server.start();
        controller = new ConvertPDFToOfx();
        ReflectionTestUtils.setField(
                controller,
                "serviceUrl",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    @AfterEach
    void stopService() {
        server.stop(0);
    }

    private static ConvertPdfToOfxRequest request(String conta) {
        ConvertPdfToOfxRequest request = new ConvertPdfToOfxRequest();
        request.setFileInput(
                new MockMultipartFile(
                        "fileInput", "extrato.pdf", "application/pdf", "%PDF-1.4".getBytes()));
        request.setConta(conta);
        return request;
    }

    private void serviceAnswers(int status, String body) {
        this.status = status;
        this.responseBody = body.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void typedAccountGoesToTheService() throws Exception {
        this.status = 200;
        this.responseBody = OFX;

        controller.processPdfToOfx(request(" 27346-5 "));

        assertEquals("/ofx/api/converter", receivedPath.get());
        assertTrue(receivedBody.get().contains("name=\"conta\"\r\n\r\n27346-5\r\n"));
        assertTrue(receivedBody.get().contains("name=\"exigir_conferencia\"\r\n\r\ntrue\r\n"));
    }

    @Test
    void blankAccountIsNotSent() throws Exception {
        this.status = 200;
        this.responseBody = OFX;

        controller.processPdfToOfx(request("  "));

        assertFalse(receivedBody.get().contains("name=\"conta\""));
    }

    @Test
    void accountWithALineBreakIsRefusedBeforeCallingTheService() {
        // It would open a new multipart field and could switch the balance check off.
        serviceAnswers(200, "");

        IllegalArgumentException error =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                controller.processPdfToOfx(
                                        request("1\r\n--x\r\nContent-Disposition: form-data;")));
        assertEquals(ConvertPDFToOfx.INVALID_ACCOUNT, error.getMessage());
        assertNull(receivedPath.get());
    }

    @Test
    void statementWithoutTheAccountPointsAtTheField() {
        serviceAnswers(
                422,
                "{\"ok\":false,\"erro\":\"O documento extraído não pode virar um OFX confiável:"
                        + " Conta sem número — o Questor não casa o arquivo com a conta.\","
                        + "\"avisos\":[],\"dados\":{\"precisa_conta\":true}}");

        IllegalArgumentException error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> controller.processPdfToOfx(request(null)));

        assertEquals(ConvertPDFToOfx.NEEDS_ACCOUNT, error.getMessage());
    }

    @Test
    void accountRefusedByTheServicePointsAtTheField() {
        serviceAnswers(
                422,
                "{\"ok\":false,\"erro\":\"A conta '12/3' não parece um número de conta.\","
                        + "\"dados\":{\"conta_invalida\":true}}");

        IllegalArgumentException error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> controller.processPdfToOfx(request("27346-5")));

        assertEquals(ConvertPDFToOfx.INVALID_ACCOUNT, error.getMessage());
    }

    @Test
    void bothAccountMessagesNameTheFieldTheConvertToolLooksFor() {
        // useConvertOperation.ts (ofxAccountRefusal) highlights the field on this text.
        for (String message :
                new String[] {ConvertPDFToOfx.NEEDS_ACCOUNT, ConvertPDFToOfx.INVALID_ACCOUNT}) {
            assertTrue(message.contains("campo \"Número da conta\""), message);
        }
    }

    @Test
    void otherRefusalsAreKeptAsIs() {
        serviceAnswers(
                422,
                "{\"ok\":false,\"erro\":\"Layout desconhecido.\","
                        + "\"dados\":{\"layout_desconhecido\":true}}");

        IllegalArgumentException error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> controller.processPdfToOfx(request(null)));

        assertEquals("OFX não gerado: Layout desconhecido.", error.getMessage());
    }
}
