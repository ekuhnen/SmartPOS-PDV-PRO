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
 * Corrige problemas de parâmetros e implementa alinhamento dinâmico.
 */
public class PrinterUtil4 {

    private static final String TAG = "PrinterUtil4";
    private static final int PAGE_WIDTH = 384; // 58mm / 203dpi
    private static final String DEFAULT_FONT = "simsun";

    // Constantes de estilo da documentação oficial
    private static final int STYLE_NORMAL = 0;
    private static final int STYLE_UNDERLINE = 0x0001;
    private static final int STYLE_ITALIC = 0x0002;
    private static final int STYLE_BOLD = 0x0004;

    // Rotação
    private static final int ROTATE_0 = 0;

    // Formato de quebra de linha (0 = word wrap, 1 = no wrap)
    private static final int FORMAT_WORD_WRAP = 0;
    private static final int FORMAT_NO_WRAP = 1;

    // Margens e Espaçamentos
    private static final int LEFT_MARGIN = 0;
    private static final int DIVIDER_MARGIN = 0;
    private static final int LINE_SPACING = 8;

    // REDUZIDO O ESPAÇAMENTO DE SEÇÃO PARA CORRIGIR O PROBLEMA REPORTADO
    private static final int SECTION_SPACING = 4;
    private static final int SECTION_SPACING_ = 6;

    private static final int BOTTOM_FEED = 140;

    // Colunas ajustadas
    private static final int LABEL_COL_WIDTH = 138;
    private static final int GAP_BETWEEN_COLS = 8;
    private static final int VALUE_COL_X = LABEL_COL_WIDTH + GAP_BETWEEN_COLS;
    private static final int VALUE_COL_WIDTH = PAGE_WIDTH - VALUE_COL_X;

    // Limites de logo
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

                    // 1) CABEÇALHOS: somente estes dois em negrito + centralizados
                    y += printTextCentered(pm, "COMPROBANTE DE PAGO", y, DEFAULT_FONT, tamanhoFonte1, SECTION_SPACING_);

                    // 2) LOGO com escala proporcional
                    try {
                        // 1. Força o Android a manter os pixels exatos (354x132), sem redimensionar
                        BitmapFactory.Options options = new BitmapFactory.Options();
                        options.inScaled = false;

                        Bitmap bitmap = BitmapFactory.decodeResource(context.getResources(), R.drawable.logo_mall, options);

                        if (bitmap != null) {
                            int xLogo = (384 - bitmap.getWidth()) / 2;
                            if (xLogo < 0) xLogo = 0; // Garantia caso a imagem seja maior que 384

                            // 2. O método drawBitmap devolve a altura exata consumida na impressão
                            int alturaImpressa = pm.drawBitmap(bitmap, xLogo, y);

                            // 3. Incrementamos o Y com o valor real gasto pela impressora
                            if (alturaImpressa > 0) {
                                y += alturaImpressa + SECTION_SPACING;
                            } else {
                                y += bitmap.getHeight() + SECTION_SPACING; // Fallback de segurança se retornar -1
                            }
                        }
                    } catch (Exception e) {
                        Log.e("PRINTER", "Erro logo: " + e.getMessage());
                    }

                    String titulo = safe(
                            saleDayItems != null ? saleDayItems.getTitle() : Value.getTypeComprov(context)
                    );

                    // Removido o SECTION_SPACING_ excessivo para resolver o problema de espaçamento
                    y += printTextCentered(pm, titulo, y, DEFAULT_FONT, tamanhoFonte1, 2);

                    // Removido o y += 10 extra que estava causando muito espaço
                    y += 2;

                    String loja = safe(
                            saleDayItems != null ? saleDayItems.getMerchantName() : Value.getNomeEmpresa(context)
                    );
                    if (!loja.isEmpty()) {
                        y = drawWrappedLeft(pm, loja, y, DEFAULT_FONT, fontBody) + 2;
                    }

                    y = drawDivider(pm, y) + 2;

                    // 4) Dados da transação
                    y = drawLabelValueRightAligned(pm,
                            "COD. TRANS.:",
                            safe(saleDayItems != null ? saleDayItems.getCodTransacao() : Value.getCodOperacao(context)),
                            y,
                            DEFAULT_FONT,
                            fontBody
                    );

                    String dataHora = buildDataHora(context, saleDayItems);
                    y = drawLabelValueRightAligned(pm, "DATA:", dataHora, y, DEFAULT_FONT, fontBody);

                    y = drawDivider(pm, y) + 2;

                    // 5) Pagador
                    String nome = safe(saleDayItems != null ? saleDayItems.getCustomerName() : Value.getNomePagador(context));
                    y = drawLabelValueRightAligned(pm, "NOMBRE:", nome, y, DEFAULT_FONT, fontBody);

                    if (isPIX) {
                        String doc = safe(saleDayItems != null ? saleDayItems.getDocCliente() : Value.getDocCliente(context));
                        y = drawLabelValueRightAligned(pm, "DOCUMENTO:", doc, y, DEFAULT_FONT, fontBody);
                    }

                    y = drawDivider(pm, y) + 2;

                    // 6) Valores
                    if (isPIX) {
                        String valorUsd = prefixIfMissing(
                                safe(saleDayItems != null ? saleDayItems.getValorDolar() : String.valueOf(Value.getValorEmUSD(context))),
                                "USD "
                        );
                        y = drawLabelValueRightAligned(pm, "VALOR EN DOLARES", valorUsd, y, DEFAULT_FONT, fontBody);
                    } else {
                        String valorNormal = safe(saleDayItems != null ? saleDayItems.getValorPago() : Value.getValorPago(context));
                        y = drawLabelValueRightAligned(pm, "VALOR", valorNormal, y, DEFAULT_FONT, fontBody);
                    }

                    if (isCliente) {
                        String valorVenda = saleDayItems != null
                                ? Util.subValoresMonetarios(saleDayItems.getValorPago(), saleDayItems.getTaxadeServico())
                                : Util.subValoresMonetarios(Value.getValorPago(context), Value.getTaxadeServico(context));
                        y = drawLabelValueRightAligned(pm, "VALOR VENDA", safe(valorVenda), y, DEFAULT_FONT, fontBody);

                        boolean temTaxa = (saleDayItems != null && Util.isGreaterThanZero(saleDayItems.getTaxadeServico()))
                                || Util.isGreaterThanZero(Value.getTaxadeServico(context));

                        if (temTaxa) {
                            String taxa = "R$ " + (
                                    saleDayItems != null
                                            ? Util.formatToTwoDecimalPlaces(saleDayItems.getTaxadeServico())
                                            : Util.formatToTwoDecimalPlaces(Value.getTaxadeServico(context))
                            );
                            y = drawLabelValueRightAligned(pm, "TAXA SERV / IOF", taxa, y, DEFAULT_FONT, fontBody);
                        }
                    }

                    y = drawDivider(pm, y) + 2;

                    // TOTAL em negrito
                    String totalPago = safe(saleDayItems != null ? saleDayItems.getValorPago() : Value.getValorPago(context));
                    y = drawLabelValueRightAligned(pm, "TOTAL PAGO", totalPago, y, DEFAULT_FONT, fontBody);

                    y += 8;
                    y = drawDivider(pm, y) + 2;

                    // 7) Rodapé
                    String numDoc = safe(
                            saleDayItems != null
                                    ? saleDayItems.getSerialNumber()
                                    : String.valueOf(Value.getReferenciaInterna(context))
                    );
                    y = drawLabelValueRightAligned(pm, "Nº DOCUMENTO", numDoc, y, DEFAULT_FONT, fontSmall);

                    if (isPIX) {
                        y = drawLeft(pm, "IOF INCLUIDO", y, DEFAULT_FONT, fontSmall, false) + 4;
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

    public static int printTextCentered(PrinterManager mPrinterManager, String texto, int y, String fontName, int tamanhoFonte, int lineSpacing) {
        if (texto == null || mPrinterManager == null) return y;

        // 1. Usar o Paint do Android para medir a largura exata do texto
        Paint paint = new Paint();
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
        return y + positiveHeight(h) + LINE_SPACING;
    }

    private static int drawWrappedLeft(PrinterManager pm, String text, int y, String font, int size) {
        List<String> lines = wrapByEstimatedChars(text, estimateChars(PAGE_WIDTH, size));
        for (String line : lines) {
            y = drawLeft(pm, line, y, font, size, false);
        }
        return y;
    }

    private static int drawLabelValue(PrinterManager pm, String label, String value, int y, String font, int size) {
        return drawLabelValueInternal(pm, label, value, y, font, size, false);
    }

    private static int drawLabelValueBold(PrinterManager pm, String label, String value, int y, String font, int size) {
        return drawLabelValueInternal(pm, label, value, y, font, size, true);
    }

    private static int drawLabelValueInternal(PrinterManager pm, String label, String value, int y, String font, int size, boolean bold) {
        String safeLabel = safe(label);
        String safeValue = safe(value);
        int style = bold ? STYLE_BOLD : STYLE_NORMAL;

        // Desenha o rótulo à esquerda
        int labelHeight = pm.drawTextEx(
                safeLabel,
                LEFT_MARGIN,
                y,
                LABEL_COL_WIDTH,
                -1,
                font,
                size,
                ROTATE_0,
                style,
                FORMAT_WORD_WRAP
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
                    ROTATE_0,
                    style,
                    FORMAT_WORD_WRAP
            );
            int inc = positiveHeight(h);
            totalValueHeight += inc;
            currentY += inc;
        }

        int usedHeight = Math.max(positiveHeight(labelHeight), totalValueHeight);
        return y + usedHeight + LINE_SPACING;
    }

    /**
     * Novo método para desenhar Label e Value, mas com o Value alinhado à direita
     */
    private static int drawLabelValueRightAligned(PrinterManager pm, String label, String value, int y, String font, int size) {
        String safeLabel = safe(label);
        String safeValue = safe(value);

        // 1. Desenha o rótulo à esquerda normalmente
        int labelHeight = pm.drawTextEx(
                safeLabel,
                LEFT_MARGIN,
                y,
                LABEL_COL_WIDTH,
                -1,
                font,
                size,
                ROTATE_0,
                STYLE_NORMAL,
                FORMAT_WORD_WRAP
        );

        // 2. Medir a largura exata do texto do valor
        Paint paint = new Paint();
        paint.setTextSize(size);
        int textWidth = (int) paint.measureText(safeValue);

        // 3. Calcular a posição X para alinhar à direita (Largura da página - Largura do texto)
        // Deixando uma pequena margem direita de 8 pixels
        int rightMargin = 8;
        int startX = PAGE_WIDTH - textWidth - rightMargin;

        // Garantir que não sobreponha a coluna do label
        if (startX < VALUE_COL_X) {
            startX = VALUE_COL_X;
        }

        // 4. Desenha o valor alinhado à direita
        int valueHeight = pm.drawTextEx(
                safeValue,
                startX,
                y,
                PAGE_WIDTH - startX,
                -1,
                font,
                size,
                ROTATE_0,
                STYLE_NORMAL,
                FORMAT_WORD_WRAP
        );

        int usedHeight = Math.max(positiveHeight(labelHeight), positiveHeight(valueHeight));
        return y + usedHeight + LINE_SPACING;
    }

    private static int drawDivider(PrinterManager pm, int y) {
        int lineY = y + 4;
        // Usa drawLine que é mais preciso que strings de hífens
        int ret = pm.drawLine(DIVIDER_MARGIN, lineY, PAGE_WIDTH - DIVIDER_MARGIN, lineY, 2);

        if (ret >= 0) {
            return y + 10;
        } else {
            // Fallback se drawLine falhar
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 48; i++) sb.append("-");
            pm.drawTextEx(sb.toString(), 0, y, PAGE_WIDTH, -1, DEFAULT_FONT, 24, ROTATE_0, STYLE_NORMAL, FORMAT_WORD_WRAP);
            return y + 10;
        }
    }

    private static int positiveHeight(int h) {
        // Altura mínima mais razoável baseada em fontes típicas
        return h > 0 ? h : 24;
    }

    private static int estimateChars(int widthPx, int fontSize) {
        // Heurística ajustada para fontes monoespaciais típicas de impressoras térmicas
        int approxCharWidth = Math.max(8, (int) (fontSize * 0.55f));
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
        if (v.isEmpty()) return v;
        return v.startsWith(prefix.trim()) ? v : prefix + v;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
