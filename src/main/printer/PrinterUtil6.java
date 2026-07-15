package com.br.plugpay.printer;

import android.content.Context;
import android.device.PrinterManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Paint;
import android.util.Log;

import com.br.plugpay.R;
import com.br.plugpay.model.SaleDayItems;
import com.br.plugpay.preferences.Value;
import com.br.plugpay.util.Util;

import java.util.ArrayList;
import java.util.List;

/**
 * PrinterUtil8 - versão reestruturada
 *
 * Melhorias:
 * - Corrige salto duplo de Y
 * - Corrige alinhamento da coluna da direita
 * - Quebra de linha por largura real em pixels
 * - Layout mais previsível
 * - Métodos pequenos e coesos
 * - Mais fiel à API PrinterManager
 */
public final class PrinterUtil6 {

    private static final String TAG = "PrinterUtil8";

    // Printer page
    private static final int PAGE_WIDTH = 384; // 58mm
    private static final int PAGE_HEIGHT_AUTO = -1;
    private static final String DEFAULT_FONT = "simsun";

    // PrinterManager styles
    private static final int STYLE_NORMAL = 0;
    private static final int STYLE_UNDERLINE = 0x0001;
    private static final int STYLE_ITALIC = 0x0002;
    private static final int STYLE_BOLD = 0x0004;

    // Rotation
    private static final int ROTATE_0 = 0;

    // Text mode
    private static final int FORMAT_WORD_WRAP = 0;
    private static final int FORMAT_NO_WRAP = 1;

    // Layout spacing
    private static final int LEFT_MARGIN = 0;
    private static final int TOP_MARGIN = 0;
    private static final int SECTION_GAP = 10;
    private static final int SMALL_GAP = 4;
    private static final int LINE_GAP = 6;
    private static final int DIVIDER_HEIGHT = 10;
    private static final int BOTTOM_FEED = 140;

    // Columns
    private static final int LABEL_COL_WIDTH = 138;
    private static final int COL_GAP = 8;
    private static final int VALUE_COL_X = LABEL_COL_WIDTH + COL_GAP;
    private static final int VALUE_COL_WIDTH = PAGE_WIDTH - VALUE_COL_X;

    // Logo limits
    private static final int MAX_LOGO_WIDTH = 190;
    private static final int MAX_LOGO_HEIGHT = 72;



    public static void imprimirComprovantePOSI9100(
            final Context context,
            final SaleDayItems saleDayItems,
            final boolean isPIX,
            final boolean isCliente,
            final int tamanhoFonte1,
            final int tamanhoFonte2
    ) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                PrinterManager pm = null;

                try {
                    pm = new PrinterManager();
                    pm.open();

                    int status = pm.getStatus();
                    if (status != 0) {
                        Log.e(TAG, "Impressora não está pronta. Status=" + status);
                        return;
                    }

                    pm.setupPage(PAGE_WIDTH, PAGE_HEIGHT_AUTO);
                    pm.clearPage();
                    pm.setGrayLevel(3);

                    ReceiptData data = ReceiptData.from(context, saleDayItems, isPIX, isCliente);

                    ReceiptStyle style = new ReceiptStyle(
                            Math.max(14, tamanhoFonte1), // header
                            Math.max(12, tamanhoFonte1), // body
                            Math.max(10, tamanhoFonte2)  // small
                    );

                    ReceiptCanvas canvas = new ReceiptCanvas(pm, style);

                    // Header
                    canvas.moveTo(TOP_MARGIN);
                    canvas.drawCentered("COMPROBANTE DE PAGO", true);
                    canvas.gap(SMALL_GAP);

                    // Logo
                    canvas.drawLogo(context, R.drawable.logo_mall, false);
                    canvas.gap(SMALL_GAP);

                    // Title
                    if (!isEmpty(data.titulo)) {
                        canvas.drawCentered(data.titulo, true);
                        canvas.gap(SMALL_GAP);
                    }

                    // Merchant
                    if (!isEmpty(data.loja)) {
                        canvas.drawTextBlockLeft(data.loja, style.bodyFont, false);
                    }

                    canvas.drawDivider();

                    // Transaction data
                    canvas.drawField("COD. TRANS.:", data.codTransacao, false);
                    canvas.drawField("DATA:", data.dataHora, false);

                    canvas.drawDivider();

                    // Customer
                    canvas.drawField("NOMBRE:", data.nomeCliente, false);

                    if (isPIX && !isEmpty(data.documentoCliente)) {
                        canvas.drawField("DOCUMENTO:", data.documentoCliente, false);
                    }

                    canvas.drawDivider();

                    // Values
                    if (isPIX && !isEmpty(data.valorDolar)) {
                        canvas.drawField("VALOR EN DOLARES", data.valorDolar, false);
                    } else {
                        canvas.drawField("VALOR", data.valorPago, false);
                    }

                    if (isCliente && !isEmpty(data.valorVenda)) {
                        canvas.drawField("VALOR VENDA", data.valorVenda, false);

                        if (!isEmpty(data.taxaServico)) {
                            canvas.drawField("TAXA SERV / IOF", data.taxaServico, false);
                        }
                    }

                    canvas.drawDivider();

                    // Total
                    canvas.drawField("TOTAL PAGO", data.valorPago, true);

                    canvas.drawDivider();

                    // Footer
                    canvas.drawField("Nº DOCUMENTO", data.numeroDocumento, false);

                    if (isPIX) {
                        canvas.drawLeft("IOF INCLUIDO", style.smallFont, false);
                    }

                    canvas.gap(24);

                    pm.printPage(0);
                    pm.paperFeed(BOTTOM_FEED);

                } catch (Exception e) {
                    Log.e(TAG, "Erro ao imprimir comprovante", e);
                } finally {
                    if (pm != null) {
                        try {
                            pm.close();
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        }).start();
    }

    // =========================================================
    // DATA MODEL
    // =========================================================

    private static final class ReceiptData {
        String titulo;
        String loja;
        String codTransacao;
        String dataHora;
        String nomeCliente;
        String documentoCliente;
        String valorDolar;
        String valorPago;
        String valorVenda;
        String taxaServico;
        String numeroDocumento;

        static ReceiptData from(Context context, SaleDayItems item, boolean isPIX, boolean isCliente) {
            ReceiptData d = new ReceiptData();

            d.titulo = safe(item != null ? item.getTitle() : Value.getTypeComprov(context));
            d.loja = safe(item != null ? item.getMerchantName() : Value.getNomeEmpresa(context));
            d.codTransacao = safe(item != null ? item.getCodTransacao() : Value.getCodOperacao(context));
            d.dataHora = buildDataHora(context, item);
            d.nomeCliente = safe(item != null ? item.getCustomerName() : Value.getNomePagador(context));
            d.documentoCliente = safe(item != null ? item.getDocCliente() : Value.getDocCliente(context));

            if (isPIX) {
                String valorUsd = safe(item != null
                        ? item.getValorDolar()
                        : String.valueOf(Value.getValorEmUSD(context)));
                d.valorDolar = prefixIfMissing(valorUsd, "USD ");
            }

            d.valorPago = safe(item != null ? item.getValorPago() : Value.getValorPago(context));

            if (isCliente) {
                d.valorVenda = safe(item != null
                        ? Util.subValoresMonetarios(item.getValorPago(), item.getTaxadeServico())
                        : Util.subValoresMonetarios(Value.getValorPago(context), Value.getTaxadeServico(context)));

                boolean temTaxa = (item != null && Util.isGreaterThanZero(item.getTaxadeServico()))
                        || Util.isGreaterThanZero(Value.getTaxadeServico(context));

                if (temTaxa) {
                    d.taxaServico = "R$ " + (
                            item != null
                                    ? Util.formatToTwoDecimalPlaces(item.getTaxadeServico())
                                    : Util.formatToTwoDecimalPlaces(Value.getTaxadeServico(context))
                    );
                }
            }

            d.numeroDocumento = safe(item != null
                    ? item.getSerialNumber()
                    : String.valueOf(Value.getReferenciaInterna(context)));

            return d;
        }
    }

    // =========================================================
    // STYLE
    // =========================================================

    private static final class ReceiptStyle {
        final int headerFont;
        final int bodyFont;
        final int smallFont;

        ReceiptStyle(int headerFont, int bodyFont, int smallFont) {
            this.headerFont = headerFont;
            this.bodyFont = bodyFont;
            this.smallFont = smallFont;
        }
    }

    // =========================================================
    // RENDER ENGINE
    // =========================================================

    private static final class ReceiptCanvas {
        private final PrinterManager pm;
        private final ReceiptStyle style;
        private int y;

        ReceiptCanvas(PrinterManager pm, ReceiptStyle style) {
            this.pm = pm;
            this.style = style;
            this.y = 0;
        }

        void moveTo(int y) {
            this.y = Math.max(0, y);
        }

        void gap(int px) {
            this.y += Math.max(0, px);
        }

        void drawCentered(String text, boolean bold) {
            if (isEmpty(text)) return;

            List<String> lines = wrapTextByWidth(text, PAGE_WIDTH, style.headerFont);
            for (String line : lines) {
                int x = calcCenterX(line, PAGE_WIDTH, style.headerFont);
                int h = pm.drawTextEx(
                        line,
                        x,
                        y,
                        PAGE_WIDTH,
                        -1,
                        DEFAULT_FONT,
                        style.headerFont,
                        ROTATE_0,
                        bold ? STYLE_BOLD : STYLE_NORMAL,
                        FORMAT_NO_WRAP
                );
                y += normalizeHeight(h, style.headerFont) + LINE_GAP;
            }
        }

        void drawLeft(String text, int fontSize, boolean bold) {
            if (isEmpty(text)) return;

            List<String> lines = wrapTextByWidth(text, PAGE_WIDTH, fontSize);
            for (String line : lines) {
                int h = pm.drawTextEx(
                        line,
                        LEFT_MARGIN,
                        y,
                        PAGE_WIDTH,
                        -1,
                        DEFAULT_FONT,
                        fontSize,
                        ROTATE_0,
                        bold ? STYLE_BOLD : STYLE_NORMAL,
                        FORMAT_NO_WRAP
                );
                y += normalizeHeight(h, fontSize) + LINE_GAP;
            }
        }

        void drawTextBlockLeft(String text, int fontSize, boolean bold) {
            drawLeft(text, fontSize, bold);
        }

        void drawField(String label, String value, boolean bold) {
            String safeLabel = safe(label);
            String safeValue = safe(value);

            List<String> labelLines = wrapTextByWidth(safeLabel, LABEL_COL_WIDTH, style.bodyFont);
            List<String> valueLines = wrapTextByWidth(safeValue, VALUE_COL_WIDTH, style.bodyFont);

            if (labelLines.isEmpty()) labelLines.add("");
            if (valueLines.isEmpty()) valueLines.add("");

            int rows = Math.max(labelLines.size(), valueLines.size());

            for (int i = 0; i < rows; i++) {
                String left = i < labelLines.size() ? labelLines.get(i) : "";
                String right = i < valueLines.size() ? valueLines.get(i) : "";

                int leftHeight = 0;
                int rightHeight = 0;

                if (!isEmpty(left)) {
                    leftHeight = pm.drawTextEx(
                            left,
                            LEFT_MARGIN,
                            y,
                            LABEL_COL_WIDTH,
                            -1,
                            DEFAULT_FONT,
                            style.bodyFont,
                            ROTATE_0,
                            bold ? STYLE_BOLD : STYLE_NORMAL,
                            FORMAT_NO_WRAP
                    );
                }

                if (!isEmpty(right)) {
                    int xRight = calcRightX(right, VALUE_COL_X, VALUE_COL_WIDTH, style.bodyFont);

                    rightHeight = pm.drawTextEx(
                            right,
                            xRight,
                            y,
                            VALUE_COL_WIDTH,
                            -1,
                            DEFAULT_FONT,
                            style.bodyFont,
                            ROTATE_0,
                            bold ? STYLE_BOLD : STYLE_NORMAL,
                            FORMAT_NO_WRAP
                    );
                }

                y += Math.max(
                        normalizeHeight(leftHeight, style.bodyFont),
                        normalizeHeight(rightHeight, style.bodyFont)
                ) + 2;
            }

            y += LINE_GAP;
        }

        void drawDivider() {
            int lineY = y + 4;
            int ret = pm.drawLine(0, lineY, PAGE_WIDTH, lineY, 2);

            if (ret >= 0) {
                y += DIVIDER_HEIGHT + SMALL_GAP;
            } else {
                // fallback
                String divider = repeat("-", 48);
                int h = pm.drawTextEx(
                        divider,
                        0,
                        y,
                        PAGE_WIDTH,
                        -1,
                        DEFAULT_FONT,
                        22,
                        ROTATE_0,
                        STYLE_NORMAL,
                        FORMAT_NO_WRAP
                );
                y += normalizeHeight(h, 22) + SMALL_GAP;
            }
        }

        void drawLogo(Context context, int drawableRes, boolean compact) {
            try {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inScaled = false;

                Bitmap original = BitmapFactory.decodeResource(context.getResources(), drawableRes, options);
                if (original == null) return;

                Bitmap bitmap = compact
                        ? resizeKeepingRatio(original, MAX_LOGO_WIDTH, MAX_LOGO_HEIGHT)
                        : original;

                int x = (PAGE_WIDTH - bitmap.getWidth()) / 2;
                if (x < 0) x = 0;

                int drawnHeight = pm.drawBitmap(bitmap, x, y);
                if (drawnHeight > 0) {
                    y += drawnHeight;
                } else {
                    y += bitmap.getHeight();
                }

                if (bitmap != original && !bitmap.isRecycled()) {
                    bitmap.recycle();
                }

                y += SECTION_GAP;

            } catch (Exception e) {
                Log.e(TAG, "Erro ao desenhar logo", e);
            }
        }
    }

    // =========================================================
    // HELPERS
    // =========================================================

    private static int calcCenterX(String text, int availableWidth, int fontSize) {
        Paint paint = new Paint();
        paint.setTextSize(fontSize);
        int textWidth = (int) Math.ceil(paint.measureText(safe(text)));
        int x = (availableWidth - textWidth) / 2;
        return Math.max(0, x);
    }

    private static int calcRightX(String text, int startX, int colWidth, int fontSize) {
        Paint paint = new Paint();
        paint.setTextSize(fontSize);
        int textWidth = (int) Math.ceil(paint.measureText(safe(text)));

        int x = startX + (colWidth - textWidth);
        if (x < startX) {
            x = startX;
        }
        return x;
    }

    private static int normalizeHeight(int h, int fontSize) {
        if (h > 0) return h;
        return Math.max(24, fontSize + 8);
    }

    private static List<String> wrapTextByWidth(String text, int maxWidthPx, int fontSize) {
        List<String> result = new ArrayList<String>();
        String source = safe(text);

        if (source.isEmpty()) {
            result.add("");
            return result;
        }

        Paint paint = new Paint();
        paint.setTextSize(fontSize);

        String[] words = source.split("\\s+");
        StringBuilder line = new StringBuilder();

        for (String word : words) {
            if (line.length() == 0) {
                if (paint.measureText(word) <= maxWidthPx) {
                    line.append(word);
                } else {
                    splitLongWordByWidth(result, word, maxWidthPx, paint);
                }
            } else {
                String candidate = line + " " + word;
                if (paint.measureText(candidate) <= maxWidthPx) {
                    line.append(" ").append(word);
                } else {
                    result.add(line.toString());
                    line.setLength(0);

                    if (paint.measureText(word) <= maxWidthPx) {
                        line.append(word);
                    } else {
                        splitLongWordByWidth(result, word, maxWidthPx, paint);
                    }
                }
            }
        }

        if (line.length() > 0) {
            result.add(line.toString());
        }

        return result;
    }

    private static void splitLongWordByWidth(List<String> lines, String word, int maxWidthPx, Paint paint) {
        StringBuilder part = new StringBuilder();

        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            String candidate = part.toString() + c;

            if (paint.measureText(candidate) <= maxWidthPx) {
                part.append(c);
            } else {
                if (part.length() > 0) {
                    lines.add(part.toString());
                    part.setLength(0);
                }
                part.append(c);
            }
        }

        if (part.length() > 0) {
            lines.add(part.toString());
        }
    }

    private static Bitmap resizeKeepingRatio(Bitmap source, int maxWidth, int maxHeight) {
        int w = source.getWidth();
        int h = source.getHeight();

        if (w <= 0 || h <= 0) return source;

        float scale = Math.min((float) maxWidth / w, (float) maxHeight / h);
        scale = Math.min(scale, 1.0f);

        int newW = Math.max(1, Math.round(w * scale));
        int newH = Math.max(1, Math.round(h * scale));

        if (newW == w && newH == h) return source;

        return Bitmap.createScaledBitmap(source, newW, newH, true);
    }

    private static String buildDataHora(Context context, SaleDayItems item) {
        if (item != null) {
            String data = safe(item.getData());
            String hora = safe(item.getHora());
            String ambos = (data + " " + hora).trim();
            if (!ambos.isEmpty()) {
                return ambos;
            }
        }
        return safe(Value.getDataHoraMovimento(context));
    }

    private static String prefixIfMissing(String value, String prefix) {
        String v = safe(value);
        if (v.isEmpty()) return v;
        return v.startsWith(prefix.trim()) ? v : prefix + v;
    }

    private static String repeat(String value, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(value);
        }
        return sb.toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean isEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }
}