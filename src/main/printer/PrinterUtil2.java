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
 * Utilitário de impressão otimizado para a API oficial do PrinterManager.
 * Corrige:
 * 1) espaçamento excessivo por soma duplicada do Y
 * 2) alinhamento da coluna de valor à direita
 * 3) quebra de linha inteligente para textos longos
 */
public class PrinterUtil2 {

    private static final String TAG = "PrinterUtil8";
    private static final int PAGE_WIDTH = 384; // 58mm / 203dpi
    private static final String DEFAULT_FONT = "simsun";

    // Estilos
    private static final int STYLE_NORMAL = 0;
    private static final int STYLE_UNDERLINE = 0x0001;
    private static final int STYLE_ITALIC = 0x0002;
    private static final int STYLE_BOLD = 0x0004;

    // Rotação
    private static final int ROTATE_0 = 0;

    // Formato de quebra
    private static final int FORMAT_WORD_WRAP = 0;
    private static final int FORMAT_NO_WRAP = 1;

    // Layout
    private static final int LEFT_MARGIN = 0;
    private static final int DIVIDER_MARGIN = 0;
    private static final int LINE_SPACING = 8;
    private static final int SECTION_SPACING = 10;
    private static final int SECTION_SPACING_LARGE = 12;
    private static final int BOTTOM_FEED = 140;

    // Colunas
    private static final int LABEL_COL_WIDTH = 138;
    private static final int GAP_BETWEEN_COLS = 8;
    private static final int VALUE_COL_X = LABEL_COL_WIDTH + GAP_BETWEEN_COLS;
    private static final int VALUE_COL_WIDTH = PAGE_WIDTH - VALUE_COL_X;

    // Logo
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

                    if (pm.getStatus() != 0) {
                        Log.e(TAG, "Impressora não está pronta. Status=" + pm.getStatus());
                        return;
                    }

                    pm.setupPage(PAGE_WIDTH, -1);
                    pm.clearPage();
                    pm.setGrayLevel(3);

                    int y = 0;
                    final int fontHeader = Math.max(14, tamanhoFonte1);
                    final int fontBody = Math.max(12, tamanhoFonte1);
                    final int fontSmall = Math.max(10, tamanhoFonte2);

                    // CABEÇALHO
                    y = printTextCentered(pm, "COMPROBANTE DE PAGO", y, DEFAULT_FONT, fontHeader, SECTION_SPACING_LARGE, false);

                    // LOGO
                    y = drawLogoOriginalCentralizado(context, pm, y, SECTION_SPACING_LARGE);

                    // TÍTULO
                    String titulo = safe(
                            saleDayItems != null ? saleDayItems.getTitle() : Value.getTypeComprov(context)
                    );
                    if (!titulo.isEmpty()) {
                        y = printTextCentered(pm, titulo, y, DEFAULT_FONT, fontHeader, SECTION_SPACING, false);
                    }

                    // Pequeno respiro visual controlado
                    y += 8;

                    // LOJA
                    String loja = safe(
                            saleDayItems != null ? saleDayItems.getMerchantName() : Value.getNomeEmpresa(context)
                    );
                    if (!loja.isEmpty()) {
                        y = drawWrappedLeft(pm, loja, y, DEFAULT_FONT, fontBody, false);
                    }

                    y = drawDivider(pm, y);

                    // DADOS DA TRANSAÇÃO
                    y = drawLabelValue(pm,
                            "COD. TRANS.:",
                            safe(saleDayItems != null ? saleDayItems.getCodTransacao() : Value.getCodOperacao(context)),
                            y,
                            DEFAULT_FONT,
                            fontBody,
                            false
                    );

                    String dataHora = buildDataHora(context, saleDayItems);
                    y = drawLabelValue(pm, "DATA:", dataHora, y, DEFAULT_FONT, fontBody, false);

                    y = drawDivider(pm, y);

                    // PAGADOR
                    String nome = safe(saleDayItems != null ? saleDayItems.getCustomerName() : Value.getNomePagador(context));
                    y = drawLabelValue(pm, "NOMBRE:", nome, y, DEFAULT_FONT, fontBody, false);

                    if (isPIX) {
                        String doc = safe(saleDayItems != null ? saleDayItems.getDocCliente() : Value.getDocCliente(context));
                        y = drawLabelValue(pm, "DOCUMENTO:", doc, y, DEFAULT_FONT, fontBody, false);
                    }

                    y = drawDivider(pm, y);

                    // VALORES
                    if (isPIX) {
                        String valorUsd = prefixIfMissing(
                                safe(saleDayItems != null ? saleDayItems.getValorDolar() : String.valueOf(Value.getValorEmUSD(context))),
                                "USD "
                        );
                        y = drawLabelValue(pm, "VALOR EN DOLARES", valorUsd, y, DEFAULT_FONT, fontBody, false);
                    } else {
                        String valorNormal = safe(saleDayItems != null ? saleDayItems.getValorPago() : Value.getValorPago(context));
                        y = drawLabelValue(pm, "VALOR", valorNormal, y, DEFAULT_FONT, fontBody, false);
                    }

                    if (isCliente) {
                        String valorVenda = saleDayItems != null
                                ? Util.subValoresMonetarios(saleDayItems.getValorPago(), saleDayItems.getTaxadeServico())
                                : Util.subValoresMonetarios(Value.getValorPago(context), Value.getTaxadeServico(context));

                        y = drawLabelValue(pm, "VALOR VENDA", safe(valorVenda), y, DEFAULT_FONT, fontBody, false);

                        boolean temTaxa = (saleDayItems != null && Util.isGreaterThanZero(saleDayItems.getTaxadeServico()))
                                || Util.isGreaterThanZero(Value.getTaxadeServico(context));

                        if (temTaxa) {
                            String taxa = "R$ " + (
                                    saleDayItems != null
                                            ? Util.formatToTwoDecimalPlaces(saleDayItems.getTaxadeServico())
                                            : Util.formatToTwoDecimalPlaces(Value.getTaxadeServico(context))
                            );
                            y = drawLabelValue(pm, "TAXA SERV / IOF", taxa, y, DEFAULT_FONT, fontBody, false);
                        }
                    }

                    y = drawDivider(pm, y);

                    // TOTAL PAGO EM NEGRITO
                    String totalPago = safe(saleDayItems != null ? saleDayItems.getValorPago() : Value.getValorPago(context));
                    y = drawLabelValue(pm, "TOTAL PAGO", totalPago, y, DEFAULT_FONT, fontBody, false);

                    y += 4;
                    y = drawDivider(pm, y);

                    // RODAPÉ
                    String numDoc = safe(
                            saleDayItems != null
                                    ? saleDayItems.getSerialNumber()
                                    : String.valueOf(Value.getReferenciaInterna(context))
                    );
                    y = drawLabelValue(pm, "Nº DOCUMENTO", numDoc, y, DEFAULT_FONT, fontSmall, false);

                    if (isPIX) {
                        y = drawLeft(pm, "IOF INCLUIDO", y, DEFAULT_FONT, fontSmall, false);
                    }

                    y += 24;

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

    /**
     * Desenha a logo original centralizada usando a altura real consumida.
     */
    private static int drawLogoOriginalCentralizado(Context context, PrinterManager pm, int y, int bottomSpacing) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;

            Bitmap bitmap = BitmapFactory.decodeResource(context.getResources(), R.drawable.logo_mall, options);

            if (bitmap == null) {
                return y;
            }

            int xLogo = (PAGE_WIDTH - bitmap.getWidth()) / 2;
            if (xLogo < 0) xLogo = 0;

            int alturaImpressa = pm.drawBitmap(bitmap, xLogo, y);

            if (alturaImpressa > 0) {
                return y + alturaImpressa + bottomSpacing;
            } else {
                return y + bitmap.getHeight() + bottomSpacing;
            }
        } catch (Exception e) {
            Log.e(TAG, "Erro ao desenhar logo original", e);
            return y;
        }
    }

    /**
     * Versão alternativa de logo redimensionada, caso queira usar no futuro.
     */
    private static int drawLogoCompacto(Context context, PrinterManager pm, int y) {
        try {
            Bitmap original = BitmapFactory.decodeResource(context.getResources(), R.drawable.logo_mall);
            if (original == null) {
                return y;
            }

            Bitmap ajustado = resizeKeepingRatio(original, MAX_LOGO_WIDTH, MAX_LOGO_HEIGHT);
            int x = (PAGE_WIDTH - ajustado.getWidth()) / 2;
            pm.drawBitmap(ajustado, x, y);

            int novoY = y + ajustado.getHeight() + 10;

            if (ajustado != original && !ajustado.isRecycled()) {
                ajustado.recycle();
            }
            return novoY;
        } catch (Exception e) {
            Log.e(TAG, "Erro ao desenhar logo", e);
            return y;
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

    /**
     * Centraliza texto corretamente e retorna o Y final.
     * IMPORTANTE: quem chama deve fazer:
     * y = printTextCentered(...)
     * e não:
     * y += printTextCentered(...)
     */
    public static int printTextCentered(
            PrinterManager pm,
            String texto,
            int y,
            String fontName,
            int tamanhoFonte,
            int lineSpacing,
            boolean bold
    ) {
        if (texto == null || pm == null) return y;

        String txt = safe(texto);
        if (txt.isEmpty()) return y;

        Paint paint = new Paint();
        paint.setTextSize(tamanhoFonte);

        int textWidth = (int) Math.ceil(paint.measureText(txt));
        int xCentro = (PAGE_WIDTH - textWidth) / 2;
        if (xCentro < 0) xCentro = 0;

        int style = bold ? STYLE_BOLD : STYLE_NORMAL;

        int h = pm.drawTextEx(
                txt,
                xCentro,
                y,
                PAGE_WIDTH,
                -1,
                fontName,
                tamanhoFonte,
                ROTATE_0,
                style,
                FORMAT_NO_WRAP
        );

        return y + positiveHeight(h, tamanhoFonte) + lineSpacing;
    }

    private static int drawLeft(PrinterManager pm, String text, int y, String font, int size, boolean bold) {
        int style = bold ? STYLE_BOLD : STYLE_NORMAL;
        int h = pm.drawTextEx(
                safe(text),
                LEFT_MARGIN,
                y,
                PAGE_WIDTH,
                -1,
                font,
                size,
                ROTATE_0,
                style,
                FORMAT_WORD_WRAP
        );
        return y + positiveHeight(h, size) + LINE_SPACING;
    }

    private static int drawWrappedLeft(PrinterManager pm, String text, int y, String font, int size, boolean bold) {
        List<String> lines = wrapTextByWidth(text, PAGE_WIDTH, size);
        int style = bold ? STYLE_BOLD : STYLE_NORMAL;

        for (String line : lines) {
            int h = pm.drawTextEx(
                    line,
                    LEFT_MARGIN,
                    y,
                    PAGE_WIDTH,
                    -1,
                    font,
                    size,
                    ROTATE_0,
                    style,
                    FORMAT_NO_WRAP
            );
            y += positiveHeight(h, size) + LINE_SPACING;
        }
        return y;
    }

    private static int drawLabelValue(PrinterManager pm, String label, String value, int y, String font, int size, boolean bold) {
        String safeLabel = safe(label);
        String safeValue = safe(value);
        int style = bold ? STYLE_BOLD : STYLE_NORMAL;

        List<String> labelLines = wrapTextByWidth(safeLabel, LABEL_COL_WIDTH, size);
        List<String> valueLines = wrapTextByWidth(safeValue, VALUE_COL_WIDTH, size);

        if (labelLines.isEmpty()) labelLines.add("");
        if (valueLines.isEmpty()) valueLines.add("");

        int totalLines = Math.max(labelLines.size(), valueLines.size());
        int currentY = y;

        for (int i = 0; i < totalLines; i++) {
            String labelLine = i < labelLines.size() ? labelLines.get(i) : "";
            String valueLine = i < valueLines.size() ? valueLines.get(i) : "";

            int labelHeight = 0;
            int valueHeight = 0;

            // Coluna esquerda
            if (!labelLine.isEmpty()) {
                labelHeight = pm.drawTextEx(
                        labelLine,
                        LEFT_MARGIN,
                        currentY,
                        LABEL_COL_WIDTH,
                        -1,
                        font,
                        size,
                        ROTATE_0,
                        style,
                        FORMAT_NO_WRAP
                );
            }

            // Coluna direita alinhada à direita
            if (!valueLine.isEmpty()) {
                int xRight = calculateRightAlignedX(valueLine, VALUE_COL_X, VALUE_COL_WIDTH, size);

                valueHeight = pm.drawTextEx(
                        valueLine,
                        xRight,
                        currentY,
                        VALUE_COL_WIDTH,
                        -1,
                        font,
                        size,
                        ROTATE_0,
                        style,
                        FORMAT_NO_WRAP
                );
            }

            int lineHeight = Math.max(
                    positiveHeight(labelHeight, size),
                    positiveHeight(valueHeight, size)
            );

            currentY += lineHeight + 2;
        }

        return currentY + LINE_SPACING;
    }

    /**
     * Calcula X para alinhar o texto à direita dentro da coluna.
     */
    private static int calculateRightAlignedX(String text, int columnX, int columnWidth, int fontSize) {
        Paint paint = new Paint();
        paint.setTextSize(fontSize);

        int textWidth = (int) Math.ceil(paint.measureText(safe(text)));
        int x = columnX + (columnWidth - textWidth);

        if (x < columnX) {
            x = columnX;
        }

        return x;
    }

    private static int drawDivider(PrinterManager pm, int y) {
        int lineY = y + 4;
        int ret = pm.drawLine(DIVIDER_MARGIN, lineY, PAGE_WIDTH - DIVIDER_MARGIN, lineY, 2);

        if (ret >= 0) {
            return y + 12;
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 48; i++) sb.append("-");
            pm.drawTextEx(
                    sb.toString(),
                    0,
                    y,
                    PAGE_WIDTH,
                    -1,
                    DEFAULT_FONT,
                    24,
                    ROTATE_0,
                    STYLE_NORMAL,
                    FORMAT_WORD_WRAP
            );
            return y + 12;
        }
    }

    /**
     * Retorna altura válida caso o driver devolva 0 ou negativo.
     */
    private static int positiveHeight(int h, int fontSize) {
        if (h > 0) return h;
        return Math.max(fontSize + 8, 24);
    }

    /**
     * Quebra texto com base em largura real em pixels.
     * Melhor que estimar quantidade de caracteres.
     */
    private static List<String> wrapTextByWidth(String text, int maxWidthPx, int fontSize) {
        List<String> lines = new ArrayList<String>();
        String source = safe(text);

        if (source.isEmpty()) {
            lines.add("");
            return lines;
        }

        Paint paint = new Paint();
        paint.setTextSize(fontSize);

        String[] words = source.split("\\s+");
        StringBuilder currentLine = new StringBuilder();

        for (String word : words) {
            if (currentLine.length() == 0) {
                if (paint.measureText(word) <= maxWidthPx) {
                    currentLine.append(word);
                } else {
                    splitLongWordByWidth(lines, word, maxWidthPx, paint);
                }
            } else {
                String testLine = currentLine.toString() + " " + word;
                if (paint.measureText(testLine) <= maxWidthPx) {
                    currentLine.append(" ").append(word);
                } else {
                    lines.add(currentLine.toString());
                    currentLine.setLength(0);

                    if (paint.measureText(word) <= maxWidthPx) {
                        currentLine.append(word);
                    } else {
                        splitLongWordByWidth(lines, word, maxWidthPx, paint);
                    }
                }
            }
        }

        if (currentLine.length() > 0) {
            lines.add(currentLine.toString());
        }

        return lines;
    }

    private static void splitLongWordByWidth(List<String> lines, String word, int maxWidthPx, Paint paint) {
        StringBuilder part = new StringBuilder();

        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            String test = part.toString() + c;

            if (paint.measureText(test) <= maxWidthPx) {
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

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}