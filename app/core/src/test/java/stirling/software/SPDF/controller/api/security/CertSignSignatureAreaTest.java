package stirling.software.SPDF.controller.api.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.apache.pdfbox.util.Matrix;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletRequest;

import stirling.software.SPDF.controller.api.security.CertSignController.SignatureArea;
import stirling.software.SPDF.model.api.security.SignPDFWithCertRequest;
import stirling.software.SPDF.service.HardwareKeyStoreService;
import stirling.software.common.service.CustomPDFDocumentFactory;
import stirling.software.common.util.TempFile;
import stirling.software.common.util.TempFileManager;

/** Placing the visible certificate signature in an area chosen on the page preview. */
class CertSignSignatureAreaTest {

    private static final float DELTA = 0.01f;

    private static PDPage page(int rotation) {
        PDPage page = new PDPage(new PDRectangle(600, 800));
        page.setRotation(rotation);
        return page;
    }

    private static void assertRect(
            float llx, float lly, float width, float height, PDRectangle actual) {
        assertEquals(llx, actual.getLowerLeftX(), DELTA, "llx");
        assertEquals(lly, actual.getLowerLeftY(), DELTA, "lly");
        assertEquals(width, actual.getWidth(), DELTA, "width");
        assertEquals(height, actual.getHeight(), DELTA, "height");
    }

    @Nested
    class Rectangle {
        // 10% from the left, 20% from the top, 30% wide and 5% tall, as the reader sees it.
        private final SignatureArea area = new SignatureArea(0.1f, 0.2f, 0.3f, 0.05f);

        @Test
        void portraitPageFlipsOnlyTheYAxis() {
            // Shown as 600x800: x=60, y=160, 180x40 from the top-left corner.
            assertRect(60, 600, 180, 40, CertSignController.signatureRectangle(page(0), area));
        }

        @Test
        void pageRotated90MapsTheDisplayedAreaBackToUserSpace() {
            // Shown as 800x600: x=80, y=120, 240x30; the widget is 30 wide and 240 tall.
            assertRect(120, 80, 30, 240, CertSignController.signatureRectangle(page(90), area));
        }

        @Test
        void pageRotated180MirrorsBothAxes() {
            assertRect(360, 160, 180, 40, CertSignController.signatureRectangle(page(180), area));
        }

        @Test
        void pageRotated270MapsTheDisplayedAreaBackToUserSpace() {
            assertRect(450, 480, 30, 240, CertSignController.signatureRectangle(page(270), area));
        }

        @Test
        void negativeRotationIsTheSameAsItsPositiveEquivalent() {
            assertRect(450, 480, 30, 240, CertSignController.signatureRectangle(page(-90), area));
        }

        @Test
        void cropBoxOriginAndSizeAreHonoured() {
            PDPage page = new PDPage(new PDRectangle(600, 800));
            page.setCropBox(new PDRectangle(10, 20, 580, 760));
            // Shown as 580x760: x=58, y=152, 174x38.
            assertRect(68, 590, 174, 38, CertSignController.signatureRectangle(page, area));
        }
    }

    @Nested
    class RequestValidation {
        private SignPDFWithCertRequest request(Float x, Float y, Float width, Float height) {
            SignPDFWithCertRequest request = new SignPDFWithCertRequest();
            request.setSignatureX(x);
            request.setSignatureY(y);
            request.setSignatureWidth(width);
            request.setSignatureHeight(height);
            return request;
        }

        @Test
        void noAreaKeepsTheDefaultPosition() {
            assertNull(CertSignController.signatureArea(request(null, null, null, null)));
        }

        @Test
        void partialAreaIsRejected() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> CertSignController.signatureArea(request(0.1f, 0.1f, 0.2f, null)));
        }

        @Test
        void areaOutsideThePageIsRejected() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> CertSignController.signatureArea(request(0.9f, 0.1f, 0.2f, 0.1f)));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> CertSignController.signatureArea(request(-0.1f, 0.1f, 0.2f, 0.1f)));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> CertSignController.signatureArea(request(0.1f, 0.1f, 0f, 0.1f)));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> CertSignController.signatureArea(request(Float.NaN, 0.1f, 0.2f, 0.1f)));
        }

        @Test
        void roundingPastTheEdgeIsClampedToThePage() {
            SignatureArea area =
                    CertSignController.signatureArea(request(0.8f, 0.9f, 0.20001f, 0.10001f));
            assertNotNull(area);
            assertEquals(0.2f, area.width(), 1e-6);
            assertEquals(0.1f, area.height(), 1e-6);
        }
    }

    @Nested
    @ExtendWith(MockitoExtension.class)
    class Signing {
        @Mock private CustomPDFDocumentFactory pdfDocumentFactory;
        @Mock private TempFileManager tempFileManager;
        @Mock private HardwareKeyStoreService hardwareKeyStoreService;
        @Mock private HttpServletRequest httpRequest;

        @InjectMocks private CertSignController certSignController;

        private byte[] pdfBytes;
        private byte[] p12Bytes;

        @BeforeEach
        void setUp() throws Exception {
            lenient()
                    .when(tempFileManager.createManagedTempFile(anyString()))
                    .thenAnswer(
                            inv -> {
                                File f =
                                        Files.createTempFile("test", inv.<String>getArgument(0))
                                                .toFile();
                                TempFile tf = mock(TempFile.class);
                                lenient().when(tf.getFile()).thenReturn(f);
                                lenient().when(tf.getPath()).thenReturn(f.toPath());
                                return tf;
                            });
            lenient()
                    .when(pdfDocumentFactory.load(any(MultipartFile.class)))
                    .thenAnswer(
                            invocation ->
                                    Loader.loadPDF(
                                            invocation.<MultipartFile>getArgument(0).getBytes()));

            // Page 1 in portrait; page 2 is a portrait page rotated to read as landscape,
            // with a CropBox that does not start at the origin.
            try (PDDocument doc = new PDDocument()) {
                doc.addPage(new PDPage(new PDRectangle(600, 800)));
                PDPage landscape = new PDPage(new PDRectangle(600, 800));
                landscape.setCropBox(new PDRectangle(10, 20, 580, 760));
                landscape.setRotation(90);
                doc.addPage(landscape);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                doc.save(baos);
                pdfBytes = baos.toByteArray();
            }
            try (InputStream is = new ClassPathResource("certs/test-cert.p12").getInputStream()) {
                p12Bytes = is.readAllBytes();
            }
        }

        private SignPDFWithCertRequest request(int pageNumber) {
            SignPDFWithCertRequest request = new SignPDFWithCertRequest();
            request.setFileInput(
                    new MockMultipartFile(
                            "fileInput", "test.pdf", MediaType.APPLICATION_PDF_VALUE, pdfBytes));
            request.setCertType("PKCS12");
            request.setP12File(
                    new MockMultipartFile(
                            "p12File", "test-cert.p12", "application/x-pkcs12", p12Bytes));
            request.setPassword("password");
            request.setShowSignature(true);
            request.setReason("test");
            request.setLocation("test");
            request.setName("tester");
            request.setPageNumber(pageNumber);
            request.setShowLogo(true);
            return request;
        }

        private byte[] sign(SignPDFWithCertRequest request) throws Exception {
            ResponseEntity<Resource> response =
                    certSignController.signPDFWithCert(request, httpRequest);
            try (InputStream in = response.getBody().getInputStream()) {
                return in.readAllBytes();
            }
        }

        /** The signature widget and the index of the page that shows it. */
        private record Placed(PDAnnotationWidget widget, int pageIndex) {}

        private Placed signatureWidget(PDDocument doc) throws Exception {
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                List<PDAnnotation> annotations = doc.getPage(i).getAnnotations();
                for (PDAnnotation annotation : annotations) {
                    if (annotation instanceof PDAnnotationWidget widget) {
                        return new Placed(widget, i);
                    }
                }
            }
            throw new AssertionError("no signature widget on any page");
        }

        /** The signature covers the whole file and its CMS verifies against the signer. */
        private void assertSignatureIntact(PDDocument doc, byte[] signedPdf) throws Exception {
            PDSignature signature = doc.getLastSignatureDictionary();
            int[] byteRange = signature.getByteRange();
            assertEquals(0, byteRange[0]);
            assertEquals(signedPdf.length, byteRange[2] + byteRange[3]);

            CMSSignedData cms =
                    new CMSSignedData(
                            new CMSProcessableByteArray(signature.getSignedContent(signedPdf)),
                            signature.getContents(signedPdf));
            SignerInformation signer = cms.getSignerInfos().getSigners().iterator().next();
            @SuppressWarnings("unchecked")
            X509CertificateHolder certificate =
                    (X509CertificateHolder)
                            cms.getCertificates().getMatches(signer.getSID()).iterator().next();
            assertTrue(
                    signer.verify(
                            new JcaSimpleSignerInfoVerifierBuilder()
                                    .setProvider("BC")
                                    .build(certificate)));
        }

        @Test
        void placesTheSignatureInTheChosenAreaOfARotatedLandscapePage() throws Exception {
            SignPDFWithCertRequest request = request(2);
            // Shown as 760x580: x=380, y=145, 190x58 from the top-left corner.
            request.setSignatureX(0.5f);
            request.setSignatureY(0.25f);
            request.setSignatureWidth(0.25f);
            request.setSignatureHeight(0.1f);

            byte[] signed = sign(request);

            try (PDDocument doc = Loader.loadPDF(signed)) {
                Placed placed = signatureWidget(doc);
                assertEquals(1, placed.pageIndex());
                // Unrotated user space: x from the top edge, y from the left, plus the CropBox.
                assertRect(155, 400, 58, 190, placed.widget().getRectangle());

                PDAppearanceStream appearance =
                        placed.widget().getAppearance().getNormalAppearance().getAppearanceStream();
                // Drawn upright (wider than tall) and counter-rotated a quarter turn.
                assertEquals(190, appearance.getBBox().getWidth(), DELTA);
                assertEquals(58, appearance.getBBox().getHeight(), DELTA);
                Matrix matrix = appearance.getMatrix();
                assertEquals(0, matrix.getScaleX(), DELTA);
                assertEquals(1, matrix.getShearY(), DELTA);
                assertEquals(-1, matrix.getShearX(), DELTA);
                assertEquals(0, matrix.getScaleY(), DELTA);

                assertSignatureIntact(doc, signed);
            }
        }

        @Test
        void placesTheSignatureInTheChosenAreaOfAPortraitPage() throws Exception {
            SignPDFWithCertRequest request = request(1);
            request.setSignatureX(0.6f);
            request.setSignatureY(0.85f);
            request.setSignatureWidth(0.35f);
            request.setSignatureHeight(0.1f);

            byte[] signed = sign(request);

            try (PDDocument doc = Loader.loadPDF(signed)) {
                Placed placed = signatureWidget(doc);
                assertEquals(0, placed.pageIndex());
                assertRect(360, 40, 210, 80, placed.widget().getRectangle());
                assertSignatureIntact(doc, signed);
            }
        }

        @Test
        void withoutAnAreaTheSignatureKeepsTheDefaultPosition() throws Exception {
            byte[] signed = sign(request(2));

            try (PDDocument doc = Loader.loadPDF(signed)) {
                Placed placed = signatureWidget(doc);
                assertEquals(1, placed.pageIndex());
                assertRect(0, 0, 200, 50, placed.widget().getRectangle());
                assertSignatureIntact(doc, signed);
            }
        }

        @Test
        void partialAreaFailsBeforeSigning() {
            SignPDFWithCertRequest request = request(1);
            request.setSignatureX(0.1f);

            assertThrows(IllegalArgumentException.class, () -> sign(request));
        }

        @Test
        void signatureFieldIsTheOneThatWasSigned() throws Exception {
            SignPDFWithCertRequest request = request(1);
            request.setSignatureX(0f);
            request.setSignatureY(0f);
            request.setSignatureWidth(1f);
            request.setSignatureHeight(1f);

            byte[] signed = sign(request);

            try (PDDocument doc = Loader.loadPDF(signed)) {
                List<PDSignatureField> fields = doc.getSignatureFields();
                assertEquals(1, fields.size());
                assertNotNull(fields.get(0).getSignature());
                assertRect(0, 0, 600, 800, signatureWidget(doc).widget().getRectangle());
            }
        }
    }
}
