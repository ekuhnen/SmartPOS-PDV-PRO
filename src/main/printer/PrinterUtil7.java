package com.br.plugpay.printer;

import android.content.Context;
import android.device.PrinterManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import com.br.plugpay.R;
import com.br.plugpay.model.SaleDayItems;
import com.br.plugpay.preferences.Value;
import com.br.plugpay.util.Util;

import java.util.ArrayList;
import java.util.List;

public class PrinterUtil7 {

    private static final String TAG = "PrinterUtil7";
    private static final int PAGE_WIDTH = 384; // 58mm / 203dpi
    private static final String DEFAULT_FONT = "simsun";

    private static final int ALIGN_LEFT = 0;
    private static final int ALIGN_CENTER = 1;
    private static final int ALIGN_RIGHT = 2;

    private static final int STYLE_NORMAL = 0;
    private static final int STYLE_BOLD = 1;

    private static final int LEFT_MARGIN = 0;
    private static final int CONTENT_WIDTH = 384;
    private static final int DIVIDER_MARGIN = 0;
    private static final int LINE_SPACING = 8;
    private static final int SECTION_SPACING = 10;
    private static final int BOTTOM_FEED = 140;

    // Colunas ajustadas para não empurrar o valor longe demais para a direita.
    private static final int LABEL_COL_WIDTH = 138;
    private static final int GAP_BETWEEN_COLS = 8;
    private static final int VALUE_COL_X = LABEL_COL_WIDTH + GAP_BETWEEN_COLS;
    private static final int VALUE_COL_WIDTH = PAGE_WIDTH - VALUE_COL_X;

    // Limite de altura do logo para não abrir um "deserto" antes do conteúdo.
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
                    int lineSpacing = 12; // Respiro entre blocos de informação
                    final int fontHeader = Math.max(14, tamanhoFonte1);
                    final int fontBody = Math.max(12, tamanhoFonte1);
                    final int fontSmall = Math.max(10, tamanhoFonte2);

                    // 1) CABEÇALHOS: somente estes dois em negrito + centralizados.
                    y += printTextCentered(pm, "COMPROBANTE DE PAGO", y, DEFAULT_FONT, tamanhoFonte1, lineSpacing);

                    // 2) LOGO com escala proporcional e altura limitada.
                    y = drawLogoCompacto(context, pm, y);

                    String titulo = (saleDayItems != null) ? saleDayItems.getTitle() : Value.getTypeComprov(context);
                    y += printTextCentered(pm, titulo, y, DEFAULT_FONT, tamanhoFonte1, lineSpacing);

                    String loja = safe(
                            saleDayItems != null ? saleDayItems.getMerchantName() : Value.getNomeEmpresa(context)
                    );

                    if (!loja.isEmpty()) {
                        y = drawWrappedLeft(pm, loja, y, DEFAULT_FONT, fontBody) + 2;
                    }

                    y = drawDivider(pm, y) + 2;

                    // 4) Dados da transação.
                    y = drawLabelValue(pm,
                            "COD. TRANS.:",
                            safe(saleDayItems != null ? saleDayItems.getCodTransacao() : Value.getCodOperacao(context)),
                            y,
                            DEFAULT_FONT,
                            fontBody
                    );

                    String dataHora = buildDataHora(context, saleDayItems);
                    y = drawLabelValue(pm, "DATA:", dataHora, y, DEFAULT_FONT, fontBody);

                    y = drawDivider(pm, y) + 2;

                    // 5) Pagador.
                    String nome = safe(saleDayItems != null ? saleDayItems.getCustomerName() : Value.getNomePagador(context));
                    y = drawLabelValue(pm, "NOMBRE:", nome, y, DEFAULT_FONT, fontBody);

                    if (isPIX) {
                        String doc = safe(saleDayItems != null ? saleDayItems.getDocCliente() : Value.getDocCliente(context));
                        y = drawLabelValue(pm, "DOCUMENTO:", doc, y, DEFAULT_FONT, fontBody);
                    }

                    y = drawDivider(pm, y) + 2;

                    // 6) Valores. Sem negrito.
                    if (isPIX) {
                        String valorUsd = prefixIfMissing(
                                safe(saleDayItems != null ? saleDayItems.getValorDolar() : String.valueOf(Value.getValorEmUSD(context))),
                                "USD "
                        );
                        y = drawLabelValue(pm, "VALOR EN DOLARES", valorUsd, y, DEFAULT_FONT, fontBody);
                    } else {
                        String valorNormal = safe(saleDayItems != null ? saleDayItems.getValorPago() : Value.getValorPago(context));
                        y = drawLabelValue(pm, "VALOR", valorNormal, y, DEFAULT_FONT, fontBody);
                    }

                    if (isCliente) {
                        String valorVenda = saleDayItems != null
                                ? Util.subValoresMonetarios(saleDayItems.getValorPago(), saleDayItems.getTaxadeServico())
                                : Util.subValoresMonetarios(Value.getValorPago(context), Value.getTaxadeServico(context));
                        y = drawLabelValue(pm, "VALOR VENDA", safe(valorVenda), y, DEFAULT_FONT, fontBody);

                        boolean temTaxa = (saleDayItems != null && Util.isGreaterThanZero(saleDayItems.getTaxadeServico()))
                                || Util.isGreaterThanZero(Value.getTaxadeServico(context));

                        if (temTaxa) {
                            String taxa = "R$ " + (
                                    saleDayItems != null
                                            ? Util.formatToTwoDecimalPlaces(saleDayItems.getTaxadeServico())
                                            : Util.formatToTwoDecimalPlaces(Value.getTaxadeServico(context))
                            );
                            y = drawLabelValue(pm, "TAXA SERV / IOF", taxa, y, DEFAULT_FONT, fontBody);
                        }
                    }

                    y = drawDivider(pm, y) + 2;

                    String totalPago = safe(saleDayItems != null ? saleDayItems.getValorPago() : Value.getValorPago(context));
                    y = drawLabelValue(pm, "TOTAL PAGO", totalPago, y, DEFAULT_FONT, fontBody);

                    y += 8;
                    y = drawDivider(pm, y) + 2;

                    // 7) Rodapé.
                    String numDoc = safe(
                            saleDayItems != null
                                    ? saleDayItems.getSerialNumber()
                                    : String.valueOf(Value.getReferenciaInterna(context))
                    );
                    y = drawLabelValue(pm, "Nº DOCUMENTO", numDoc, y, DEFAULT_FONT, fontSmall);

                    if (isPIX) {
                        y = drawLeft(pm, "IOF INCLUIDO", y, DEFAULT_FONT, fontSmall, false) + 4;
                    }

                    // Espaço final extra para o papel correr mais e nada ficar "escondido".
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

    public static int printTextCentered(PrinterManager mPrinterManager, String texto, int y, String fontName, int tamanhoFonte, int lineSpacing) {
        if (texto == null || mPrinterManager == null) return y;

        // 1. Usar o Paint do Android para medir a largura exata do texto
        android.graphics.Paint paint = new android.graphics.Paint();
        paint.setTextSize(tamanhoFonte);

        int textWidth = (int) paint.measureText(texto);

        // 2. Calcular a posição X para centralizar na bobina de 384 pixels
        int xCentro = (384 - textWidth) / 2;

        // Garantir que a margem não fique negativa se o texto for muito longo
        if (xCentro < 0) {
            xCentro = 0;
        }

        // 3. Executar a impressão na posição calculada e capturar a altura gasta
        int heightGasto = mPrinterManager.drawTextEx(texto, xCentro, y, 384, -1, fontName, tamanhoFonte, 0, 0, 0);

        // 4. Retornar o novo Y atualizado para a próxima linha
        return y + heightGasto + lineSpacing;
    }


    private static Bitmap resizeKeepingRatio(Bitmap source, int maxWidth, int maxHeight) {
        int w = source.getWidth();
        int h = source.getHeight();

        if (w <= 0 || h <= 0) {
            return source;
        }

        float scale = Math.min((float) maxWidth / w, (float) maxHeight / h);
        scale = Math.min(scale, 1.0f);

        int newW = Math.max(1, Math.round(w * scale));
        int newH = Math.max(1, Math.round(h * scale));

        if (newW == w && newH == h) {
            return source;
        }

        return Bitmap.createScaledBitmap(source, newW, newH, true);
    }

    private static int drawCentered(PrinterManager pm, String text, int y, String font, int size, boolean bold) {
        return y + positiveHeight(pm.drawTextEx(
                safe(text),
                LEFT_MARGIN,
                y,
                CONTENT_WIDTH,
                -1,
                font,
                size,
                bold ? STYLE_BOLD : STYLE_NORMAL,
                ALIGN_CENTER,
                1
        )) + LINE_SPACING;
    }

    private static int drawLeft(PrinterManager pm, String text, int y, String font, int size, boolean bold) {
        return y + positiveHeight(pm.drawTextEx(
                safe(text),
                LEFT_MARGIN,
                y,
                CONTENT_WIDTH,
                -1,
                font,
                size,
                bold ? STYLE_BOLD : STYLE_NORMAL,
                ALIGN_LEFT,
                0
        )) + LINE_SPACING;
    }

    private static int drawWrappedLeft(PrinterManager pm, String text, int y, String font, int size) {
        List<String> lines = wrapByEstimatedChars(text, estimateChars(CONTENT_WIDTH, size));
        for (String line : lines) {
            y = drawLeft(pm, line, y, font, size, false);
        }
        return y;
    }

    private static int drawLabelValue(PrinterManager pm, String label, String value, int y, String font, int size) {
        String safeLabel = safe(label);
        String safeValue = safe(value);

        int labelHeight = pm.drawTextEx(
                safeLabel,
                LEFT_MARGIN,
                y,
                LABEL_COL_WIDTH,
                -1,
                font,
                size,
                STYLE_NORMAL,
                ALIGN_LEFT,
                0
        );

        List<String> valueLines = wrapByEstimatedChars(safeValue, estimateChars(VALUE_COL_WIDTH, size));
        if (valueLines.isEmpty()) {
            valueLines.add("");
        }

        int currentY = y;
        int totalValueHeight = 0;
        for (String line : valueLines) {
            int h = pm.drawTextEx(
                    line,
                    VALUE_COL_X,
                    currentY,
                    VALUE_COL_WIDTH,
                    -1,
                    font,
                    size,
                    STYLE_NORMAL,
                    ALIGN_RIGHT,
                    2
            );
            int inc = positiveHeight(h);
            totalValueHeight += inc;
            currentY += inc;
        }

        int usedHeight = Math.max(positiveHeight(labelHeight), totalValueHeight);
        return y + usedHeight + LINE_SPACING;
    }

    private static int drawDivider(PrinterManager pm, int y) {
        int startX = DIVIDER_MARGIN;
        int endX = PAGE_WIDTH - DIVIDER_MARGIN;
        int lineY = y + 4;

        // linha contínua cobrindo praticamente toda a largura.
        pm.drawLine(startX, lineY, endX, lineY, 1);
        return y + 10;
    }

    private static int positiveHeight(int h) {
        return h > 0 ? h : 18;
    }

    private static int estimateChars(int widthPx, int fontSize) {
        int approxCharWidth = Math.max(6, (int) (fontSize * 0.65f));
        return Math.max(4, widthPx / approxCharWidth);
    }

    private static List<String> wrapByEstimatedChars(String text, int maxChars) {
        List<String> lines = new ArrayList<String>();
        String source = safe(text).trim();

        if (source.isEmpty()) {
            lines.add("");
            return lines;
        }

        if (source.length() <= maxChars) {
            lines.add(source);
            return lines;
        }

        String[] words = source.split("\\s+");
        StringBuilder current = new StringBuilder();

        for (String word : words) {
            if (word.length() > maxChars) {
                if (current.length() > 0) {
                    lines.add(current.toString().trim());
                    current.setLength(0);
                }
                splitLongWord(lines, word, maxChars);
                continue;
            }

            if (current.length() == 0) {
                current.append(word);
            } else if (current.length() + 1 + word.length() <= maxChars) {
                current.append(' ').append(word);
            } else {
                lines.add(current.toString().trim());
                current.setLength(0);
                current.append(word);
            }
        }

        if (current.length() > 0) {
            lines.add(current.toString().trim());
        }

        return lines;
    }

    private static void splitLongWord(List<String> lines, String word, int maxChars) {
        int start = 0;
        while (start < word.length()) {
            int end = Math.min(start + maxChars, word.length());
            lines.add(word.substring(start, end));
            start = end;
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
        if (v.isEmpty()) {
            return v;
        }
        return v.startsWith(prefix.trim()) ? v : prefix + v;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}