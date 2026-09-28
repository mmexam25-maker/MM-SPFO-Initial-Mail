package org.example;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.util.Matrix;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Adds one single Mariners Mentor watermark group to every page:
 *
 *     [LOGO]  MARINERS MENTOR
 *
 * The complete group is rotated 90 degrees and centred vertically on the page.
 * The logo is therefore NEXT TO the text, not separately at the top/right.
 */
public final class PdfWatermarkService {

    private static final String WATERMARK_TEXT = "MARINERS MENTOR";
    private static final String LOGO_RESOURCE = "/mariners-mentor-logo.png";

    private static final Color WATERMARK_COLOR = new Color(0, 82, 45);
    private static final float WATERMARK_OPACITY = 0.15f;

    private PdfWatermarkService() {
    }

    public static byte[] addMarinersMentorWatermark(byte[] sourcePdf) throws Exception {
        if (sourcePdf == null || sourcePdf.length < 5) {
            throw new IllegalArgumentException("SPFO PDF is empty - watermark cannot be added.");
        }

        byte[] logoBytes = loadLogoBytes();

        try (PDDocument document = Loader.loadPDF(sourcePdf);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {

            if (document.getNumberOfPages() == 0) {
                throw new IllegalStateException("SPFO PDF has no pages - watermark cannot be added.");
            }

            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDImageXObject logo = PDImageXObject.createFromByteArray(
                    document,
                    logoBytes,
                    "mariners-mentor-logo.png"
            );

            for (PDPage page : document.getPages()) {
                addCenteredVerticalLogoAndText(document, page, font, logo);
            }

            document.save(output);

            System.out.println(
                    "SPFO PDF WATERMARK ADDED | Layout: [LOGO] MARINERS MENTOR | " +
                            "Orientation: VERTICAL | Position: CENTER | Pages: ALL"
            );

            return output.toByteArray();
        }
    }

    /**
     * Interest-update watermark only. Keeps the normal SPFO Balance watermark
     * unchanged. Layout is horizontal: logo on the first line and
     * MARINERS MENTOR directly below it in dark green.
     */
    public static byte[] addMarinersMentorInterestWatermark(byte[] sourcePdf) throws Exception {
        if (sourcePdf == null || sourcePdf.length < 5) {
            throw new IllegalArgumentException("SPFO Interest PDF is empty - watermark cannot be added.");
        }

        byte[] logoBytes = loadLogoBytes();

        try (PDDocument document = Loader.loadPDF(sourcePdf);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {

            if (document.getNumberOfPages() == 0) {
                throw new IllegalStateException("SPFO Interest PDF has no pages.");
            }

            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDImageXObject logo = PDImageXObject.createFromByteArray(
                    document,
                    logoBytes,
                    "mariners-mentor-logo.png"
            );

            for (PDPage page : document.getPages()) {
                float pageWidth = page.getCropBox().getWidth();
                float pageHeight = page.getCropBox().getHeight();
                float lowerLeftX = page.getCropBox().getLowerLeftX();
                float lowerLeftY = page.getCropBox().getLowerLeftY();

                float centerX = lowerLeftX + pageWidth / 2.0f;
                float centerY = lowerLeftY + pageHeight / 2.0f;

                float logoWidth = Math.min(120.0f, Math.min(pageWidth, pageHeight) * 0.18f);
                float logoHeight = logo.getHeight() * (logoWidth / logo.getWidth());
                float fontSize = Math.min(34.0f, Math.min(pageWidth, pageHeight) * 0.055f);
                float textWidth = (font.getStringWidth(WATERMARK_TEXT) / 1000.0f) * fontSize;
                float gap = 10.0f;

                // Many SPFO ledger PDFs are stored with /Rotate=90 even though
                // they look landscape in the PDF viewer. If we draw normal text
                // without compensating for that page rotation, the title looks
                // VERTICAL to the user. Counter-rotate the watermark so the
                // displayed MARINERS MENTOR title is ALWAYS HORIZONTAL.
                int pageRotation = page.getRotation() % 360;
                if (pageRotation < 0) {
                    pageRotation += 360;
                }

                PDExtendedGraphicsState transparency = new PDExtendedGraphicsState();
                transparency.setNonStrokingAlphaConstant(WATERMARK_OPACITY);
                transparency.setStrokingAlphaConstant(WATERMARK_OPACITY);

                try (PDPageContentStream content = new PDPageContentStream(
                        document,
                        page,
                        PDPageContentStream.AppendMode.APPEND,
                        true,
                        true
                )) {
                    content.saveGraphicsState();
                    content.setGraphicsStateParameters(transparency);

                    // Put the local watermark origin at the physical page centre.
                    content.transform(Matrix.getTranslateInstance(centerX, centerY));

                    // Compensate for the PDF /Rotate value in the SAME direction.
                    // SPFO ledger PDFs normally use /Rotate=90. Using -90 here
                    // makes the watermark appear 180 degrees inverted after the
                    // viewer applies the page rotation. Using +90 keeps the logo
                    // and MARINERS MENTOR upright and horizontal to the user.
                    if (pageRotation != 0) {
                        content.transform(
                                Matrix.getRotateInstance(
                                        Math.toRadians(pageRotation),
                                        0.0f,
                                        0.0f
                                )
                        );
                    }

                    // Logo centred on the first line.
                    content.drawImage(
                            logo,
                            -logoWidth / 2.0f,
                            gap,
                            logoWidth,
                            logoHeight
                    );

                    // MARINERS MENTOR centred on the next line, HORIZONTAL.
                    content.setNonStrokingColor(WATERMARK_COLOR);
                    content.beginText();
                    content.setFont(font, fontSize);
                    content.newLineAtOffset(
                            -textWidth / 2.0f,
                            -fontSize - gap
                    );
                    content.showText(WATERMARK_TEXT);
                    content.endText();

                    content.restoreGraphicsState();
                }
            }

            document.save(output);
            System.out.println(
                    "SPFO INTEREST PDF WATERMARK ADDED | TITLE: HORIZONTAL | " +
                            "LOGO FIRST LINE | MARINERS MENTOR NEXT LINE | DARK GREEN | UPRIGHT PAGE ROTATION COMPENSATED"
            );
            return output.toByteArray();
        }
    }

    private static byte[] loadLogoBytes() throws Exception {
        try (InputStream in = PdfWatermarkService.class.getResourceAsStream(LOGO_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(
                        "Mariners Mentor logo resource not found: " + LOGO_RESOURCE
                );
            }
            return in.readAllBytes();
        }
    }

    private static void addCenteredVerticalLogoAndText(
            PDDocument document,
            PDPage page,
            PDType1Font font,
            PDImageXObject logo
    ) throws Exception {

        float pageWidth = page.getCropBox().getWidth();
        float pageHeight = page.getCropBox().getHeight();
        float lowerLeftX = page.getCropBox().getLowerLeftX();
        float lowerLeftY = page.getCropBox().getLowerLeftY();

        float centerX = lowerLeftX + pageWidth / 2.0f;
        float centerY = lowerLeftY + pageHeight / 2.0f;

        /*
         * We design the watermark horizontally in a local coordinate system:
         *
         *       [LOGO]  MARINERS MENTOR
         *
         * and then rotate that WHOLE local coordinate system by 90 degrees.
         * This keeps the logo immediately beside the text and centres the pair.
         */
        float fontSize = 58.0f;
        float textWidth = (font.getStringWidth(WATERMARK_TEXT) / 1000.0f) * fontSize;

        float logoHeight = fontSize * 1.15f;
        float logoWidth = logo.getWidth() * (logoHeight / logo.getHeight());
        float gap = fontSize * 0.30f;

        float groupWidth = logoWidth + gap + textWidth;
        float groupHeight = Math.max(logoHeight, fontSize);

        // If the vertical group is too tall for the page, scale everything down together.
        float maxVerticalLength = pageHeight * 0.78f;
        if (groupWidth > maxVerticalLength) {
            float scale = maxVerticalLength / groupWidth;
            fontSize *= scale;
            textWidth = (font.getStringWidth(WATERMARK_TEXT) / 1000.0f) * fontSize;
            logoHeight *= scale;
            logoWidth *= scale;
            gap *= scale;
            groupWidth = logoWidth + gap + textWidth;
            groupHeight = Math.max(logoHeight, fontSize);
        }

        // Local X is along the final vertical page direction after rotation.
        float startX = -groupWidth / 2.0f;
        float logoY = -logoHeight / 2.0f;

        // PDF text baseline correction so the text visually centres beside the logo.
        float textY = -(fontSize * 0.34f);
        float textX = startX + logoWidth + gap;

        PDExtendedGraphicsState transparency = new PDExtendedGraphicsState();
        transparency.setNonStrokingAlphaConstant(WATERMARK_OPACITY);
        transparency.setStrokingAlphaConstant(WATERMARK_OPACITY);

        try (PDPageContentStream content = new PDPageContentStream(
                document,
                page,
                PDPageContentStream.AppendMode.APPEND,
                true,
                true
        )) {
            content.saveGraphicsState();
            content.setGraphicsStateParameters(transparency);

            // Move the origin to the page centre and rotate the COMPLETE watermark group.
            content.transform(
                    Matrix.getRotateInstance(Math.toRadians(90.0), centerX, centerY)
            );

            // Logo comes first, immediately next to the text.
            content.drawImage(logo, startX, logoY, logoWidth, logoHeight);

            // Text follows the logo in the same rotated coordinate system.
            content.setNonStrokingColor(WATERMARK_COLOR);
            content.beginText();
            content.setFont(font, fontSize);
            content.newLineAtOffset(textX, textY);
            content.showText(WATERMARK_TEXT);
            content.endText();

            content.restoreGraphicsState();
        }
    }
}
