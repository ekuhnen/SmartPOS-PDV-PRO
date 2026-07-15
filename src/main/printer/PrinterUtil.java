package com.br.plugpay.printer;

import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.device.PrinterManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.br.plugpay.R;
import com.br.plugpay.model.ResumenVentas;
import com.br.plugpay.model.SaleDayItems;
import com.br.plugpay.preferences.Value;
import com.br.plugpay.util.CurrencyDisplayFormatter;
import com.br.plugpay.util.DocumentFormatter;
import com.br.plugpay.util.Util;
import com.pos.sdk.printer.POIPrinterManager;
import com.pos.sdk.printer.PosPrinter;
import com.pos.sdk.printer.models.BitmapPrintLine;
import com.pos.sdk.printer.models.PrintLine;
import com.pos.sdk.printer.models.TextPrintLine;

import java.util.ArrayList;
import java.util.List;

import woyou.aidlservice.jiuiv5.ICallback;
import woyou.aidlservice.jiuiv5.IWoyouService;

public class PrinterUtil {
    private static final String TAG = "Printer";
    private static final String TAG2 = "TESTE IMPRESSAO";
    private static final int RECEIPT_WIDTH_CHARS = 34; // Largura do recibo em caracteres para uma fonte de tamanho padrão (24)

    public static void printer(Context context, SaleDayItems saleDayItems, boolean isPIX, boolean isCLiente) {
        String log = "Inicio";

        try {
            final POIPrinterManager printerManager = new POIPrinterManager(context);
            Log.d(TAG, "TESTE TESTE printerTest B");
            log += "-A-";
            int number = PosPrinter.getNumberOfPrinters();
            log += "-A-   number= " + number;
            printerManager.open();

            try {
                printerManager.cleanCache();
            } catch (Exception e) {

            }
            log += "-B-";
            int state = printerManager.getPrinterState();
            Log.d(TAG, "printer state = " + state);
            log += "-C-";
            //printerManager.setPrintFont("/system/fonts/Android-1.ttf");
            Log.d(TAG, "TESTE TESTE printerTest C");
            printerManager.setPrintGray(3000);
            printerManager.setLineSpace(3);
            log += "-D-";
            //printerManager.cleanCache();
            String str1 = "COMPROBANTE DE PAGO";
            log += "-E-";
            PrintLine p1 = new TextPrintLine(str1, PrintLine.CENTER, 22, true);
            printerManager.addPrintLine(p1);
            printerManager.setLineSpace(1);
            log += "-F-";
            Bitmap bitmap = BitmapFactory.decodeResource(context.getResources(), R.drawable.logo_mall);
            printerManager.addPrintLine(new BitmapPrintLine(bitmap, PrintLine.CENTER));
            printerManager.setPrintFont("/system/fonts/ComingSoon.ttf");
            log += "-G-";
            printerManager.setLineSpace(1);
            printerManager.setPrintFont("/system/fonts/DroidSansMono.ttf");
            log += "-H-";
            List<TextPrintLine> list1 = printList("", (saleDayItems != null ? saleDayItems.getTitle() : Value.getTypeComprov(context)), "", 17, true);
            printerManager.addPrintLine(list1);
            printerManager.setLineSpace(1);
            List<TextPrintLine> listNombreDelEsta = printList((saleDayItems != null ? saleDayItems.getMerchantName() : Value.getNomeEmpresa(context)), "", "", 16, false);
            printerManager.addPrintLine(listNombreDelEsta);
            List<TextPrintLine> listCodTrans = printList("COD. TRANS.:", "", (saleDayItems != null ? saleDayItems.getCodTransacao() : Value.getCodOperacao(context)), 16, false);
            printerManager.addPrintLine(listCodTrans);
            List<TextPrintLine> listData = printList("DATA:", "", (saleDayItems != null ? saleDayItems.getData() + " " + saleDayItems.getHora() : Value.getDataHoraMovimento(context)), 16, false);
            printerManager.addPrintLine(listData);
            PrintLine lline = new TextPrintLine("---------------------------", PrintLine.CENTER);
            printerManager.addPrintLine(lline);
            String nomeOrCpf = "";
            Boolean isNome = false;
            if(saleDayItems != null && saleDayItems.getCustomerName() != null && !saleDayItems.getCustomerName().isEmpty()) {
                nomeOrCpf = saleDayItems.getCustomerName();
                isNome = true;
            }else
                nomeOrCpf = DocumentFormatter.formatCPF(saleDayItems.getCustomerCPF());


            List<TextPrintLine> listNombre = printList(isNome?"NOMBRE:":"CPF:", "", nomeOrCpf, 16, false);
            printerManager.addPrintLine(listNombre);
            if (isPIX) {
                List<TextPrintLine> listDoc = printList("DOCUMENTO:", "", (saleDayItems != null ? saleDayItems.getDocCliente() : Value.getDocCliente(context)), 16, false);
                printerManager.addPrintLine(listDoc);
            }
            PrintLine lline2 = new TextPrintLine("---------------------------", PrintLine.CENTER);
            printerManager.addPrintLine(lline2);
            if (isPIX) {
                List<TextPrintLine> listValorDolar = printList("VALOR EN DOLARES", "", "USD " + (saleDayItems != null ? saleDayItems.getValorDolar() : Value.getValorEmUSD(context) + ""), 16, false);
                printerManager.addPrintLine(listValorDolar);
            } else {
                List<TextPrintLine> listValorDolar = printList("VALOR", "",
                        CurrencyDisplayFormatter.formatCurrencyWithSymbol(saleDayItems != null ? saleDayItems.getValorTransacao() : Value.getValorPago(context),
                                                                          saleDayItems != null ? saleDayItems.getOrigem() : Value.getOriginCurrencie(context)), 16, false);
                printerManager.addPrintLine(listValorDolar);
            }
            PrintLine lline3 = new TextPrintLine("---------------------------", PrintLine.CENTER);
            printerManager.addPrintLine(lline3);
            printerManager.setLineSpace(2);
            if (isCLiente) {
                List<TextPrintLine> listValorVenda = printList("VALOR VENDA", "", (saleDayItems != null ? Util.subValoresMonetarios(saleDayItems.getValorPago(), saleDayItems.getTaxadeServico()) : Util.subValoresMonetarios(Value.getValorPago(context), Value.getTaxadeServico(context))), 16, false);
                printerManager.addPrintLine(listValorVenda);
            }

            if (((saleDayItems != null && Util.isGreaterThanZero(saleDayItems.getTaxadeServico())) || Util.isGreaterThanZero(Value.getTaxadeServico(context))) && isCLiente) {
                List<TextPrintLine> listTaxaServico = printList("TAXA DE SERVIÇO / IOF", "", "R$ " + (saleDayItems != null ? Util.formatToTwoDecimalPlaces(saleDayItems.getTaxadeServico()) : Util.formatToTwoDecimalPlaces(Value.getTaxadeServico(context))), 16, false);
                printerManager.addPrintLine(listTaxaServico);
            }
            if (isCLiente) {
                List<TextPrintLine> listTotalPago = printList("TOTAL PAGO", "", (saleDayItems != null ? saleDayItems.getValorPago() : Value.getValorPago(context)), 16, false);
                printerManager.addPrintLine(listTotalPago);
            } else {
                List<TextPrintLine> listTotalPago = printList("TOTAL PAGO", "", (saleDayItems != null ? saleDayItems.getValorPago() : Value.getValorPago(context)), 16, false);
                printerManager.addPrintLine(listTotalPago);
            }

            List<TextPrintLine> listDocumento = printList("Nº DOCUMENTO", "", (saleDayItems != null ? saleDayItems.getSerialNumber() : Value.getReferenciaInterna(context) + ""), 14, false);
            printerManager.addPrintLine(listDocumento);
            if (isPIX) {
                List<TextPrintLine> listIOF = printList("IOF INCLUIDO", "", "", 14, false);
                printerManager.addPrintLine(listIOF);
            }
            printerManager.addPrintLine(new TextPrintLine(" ", 0, 100));
            POIPrinterManager.IPrinterListener listener = new POIPrinterManager.IPrinterListener() {
                @Override
                public void onStart() {
                    Toast.makeText(context, "Inicinado impressão...", Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onFinish() {
                    printerManager.cleanCache();
                    printerManager.close();
                }

                @Override
                public void onError(int code, String msg) {
                    Log.e("POIPrinterManager", "code: " + code + "msg: " + msg);
                    printerManager.close();
                }
            };
            if (state == 4) {
                printerManager.close();
                return;
            }

            printerManager.beginPrint(listener);
        } catch (Exception e) {
            log += "-H- erro = " + e.getMessage();
            Log.d(TAG, "TESTE TESTE printerTest A" + e);
            showPrintDialog(context, log);
            Log.d(TAG, "TESTE TESTE printerTest d");
        } finally {
            //  showPrintDialog(context, log);
        }

    }

    public static void printResumenVentas(Context context, ResumenVentas sale) {
        try {
            final POIPrinterManager printerManager = new POIPrinterManager(context);
            printerManager.open();
            printerManager.cleanCache();

            printerManager.setPrintGray(3000);
            printerManager.setLineSpace(3);

            // 🔹 Logo
            Bitmap logo = BitmapFactory.decodeResource(
                    context.getResources(),
                    R.drawable.logo_mall
            );
            printerManager.addPrintLine(
                    new BitmapPrintLine(logo, PrintLine.CENTER)
            );

            printerManager.setLineSpace(1);

            // 🔹 Título
            printerManager.addPrintLine(
                    new TextPrintLine("RESUMEN DE VENTAS", PrintLine.CENTER, 22, true)
            );

            printerManager.addPrintLine(
                    new TextPrintLine("--------------------------------", PrintLine.CENTER)
            );

            printerManager.setLineSpace(1);

            // 🔹 Dados organizados
            printerManager.addPrintLine(
                    printLine("Fecha:", sale.getData()+ "  "+sale.getDataTime())
            );

            printerManager.addPrintLine(
                    printLine("Comercio:", sale.getMerchantName())
            );

       /*     printerManager.addPrintLine(
                    printLine("Cantidad estado:", sale.getStatus()+"")
            );

            printerManager.addPrintLine(
                    printLine("Estado:", sale.getStatusName())
            );
*/
            printerManager.addPrintLine(
                    printLine("Moneda:", sale.getOriginCurrency())
            );

            printerManager.addPrintLine(
                    printLine("Transacciones:",
                            String.valueOf(sale.getQtdTransacoes()))
            );

            printerManager.addPrintLine(
                    new TextPrintLine("--------------------------------", PrintLine.CENTER)
            );

            printerManager.setLineSpace(2);

            // 🔹 TOTAL destacado
            printerManager.addPrintLine(
                    new TextPrintLine(
                            "Total: " + sale.getTotalFormatado(),
                            PrintLine.CENTER,
                            20,
                            true
                    )
            );

            printerManager.addPrintLine(
                    new TextPrintLine(" ", 0, 80)
            );

            // 🔹 Listener
            printerManager.beginPrint(new POIPrinterManager.IPrinterListener() {
                @Override
                public void onStart() {
                    Toast.makeText(context,
                            "Iniciando impressão...",
                            Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onFinish() {
                    printerManager.cleanCache();
                    printerManager.close();
                }

                @Override
                public void onError(int code, String msg) {
                    printerManager.close();
                }
            });

        } catch (Exception e) {
           String log = "\n-Erro na impressão: " + e.getMessage();

            Log.e("PRINT_ERROR", "Erro na impressão", e);

            showPrintDialog(context,
                    log + "\n\nStack: " + Log.getStackTraceString(e)
            );
        }
    }
    /**
     * Método estático principal para imprimir um recibo usando o serviço da Sunmi.
     *
     * @param woyouService Instância do serviço da impressora Sunmi (obtida no onServiceConnected).
     * @param context      Contexto da aplicação para acessar recursos.
     * @param saleDayItems Objeto contendo os dados da venda. Pode ser nulo, nesse caso usará os dados da classe Value.
     * @param isPIX        Indica se a transação foi via PIX, para impressão condicional.
     * @param isCLiente    Indica se esta é a via do cliente, para impressão condicional.
     */
    public static void printReceipt(IWoyouService woyouService, Context context, SaleDayItems saleDayItems, boolean isPIX, boolean isCLiente) {
        if (woyouService == null) {
            Log.e(TAG, "IWoyouService is null. Cannot print.");
            Toast.makeText(context, "Serviço de impressão não está pronto.", Toast.LENGTH_SHORT).show();
            return;
        }

        // A impressão deve ocorrer em uma thread de segundo plano para não bloquear a UI
        new Thread(() -> {
            try {
                // Define the path to the desired font
                final String MONO_FONT_PATH = "/system/fonts/DroidSansMono.ttf";
                final float FONT_SIZE_NORMAL =  22; // Define a standard font size for the receipt body
                final float FONT_SIZE_HEADER = 30;
                final float FONT_SIZE_SUBHEADER = 28;


                // Callback para as operações de impressão. Pode ser compartilhado.
                ICallback.Stub callback = new ICallback.Stub() {
                    @Override
                    public void onRunResult(boolean isSuccess) throws RemoteException {
                        Log.d("SunmiPrinter", "IsSuccess = "+ isSuccess);
                    }

                    @Override
                    public void onReturnString(String result) throws RemoteException {
                        Log.d("SunmiPrinter", "result = ");
                    }

                    @Override
                    public void onRaiseException(int code, String msg) throws RemoteException {
                        Log.d("SunmiPrinter", "onRaiseException code = "+ code+"- msg = "+msg);
                    }

                    @Override
                    public void onPrintResult(int code, String msg) throws RemoteException {
                        Log.d("SunmiPrinter", "onPrintResult code = "+ code+"- msg = ");
                    }
                };

                // Inicia a preparação da impressora
                woyouService.printerInit(callback);


                byte[] centerAlign = {0x1B, 0x61, 1};
                woyouService.sendRAWData(centerAlign, callback);
                woyouService.setAlignment(1, callback); // Centralizar
                woyouService.printTextWithFont("     COMPROBANTE DE PAGO", MONO_FONT_PATH ,25, callback);


                // --- CABEÇALHO ---
                woyouService.setAlignment(1, callback); // Centralizar
                Bitmap logoBitmap = BitmapFactory.decodeResource(context.getResources(), R.drawable.logo_mall);
                if (logoBitmap != null) {
                    woyouService.printBitmap(logoBitmap, callback);
                }

                String title = (saleDayItems != null ? saleDayItems.getTitle() : Value.getTypeComprov(context));
            woyouService.printTextWithFont("          "+title + "\n", MONO_FONT_PATH,20, callback);

                // --- CORPO DO RECIBO (using DroidSansMono.ttf) ---
                woyouService.setAlignment(0, callback); // Alinhar à esquerda

         //       woyouService.lineWrap(1, callback);

                String merchantName = (saleDayItems != null ? saleDayItems.getMerchantName() : Value.getNomeEmpresa(context));
                woyouService.printTextWithFont(merchantName , MONO_FONT_PATH, FONT_SIZE_NORMAL, callback);

                String codTrans = (saleDayItems != null ? saleDayItems.getCodTransacao() : Value.getCodOperacao(context));
                woyouService.printTextWithFont(formatLine("COD. TRANS.:", codTrans), MONO_FONT_PATH, FONT_SIZE_NORMAL, callback);

                String dateTime = (saleDayItems != null ? saleDayItems.getData() + " " + saleDayItems.getHora() : Value.getDataHoraMovimento(context));
                woyouService.printTextWithFont(formatLine("DATA:", dateTime), MONO_FONT_PATH, FONT_SIZE_NORMAL, callback);

                woyouService.printTextWithFont("--------------------------------", MONO_FONT_PATH, FONT_SIZE_NORMAL, callback);

                String customerName = (saleDayItems != null ? saleDayItems.getCustomerName() : Value.getNomePagador(context));
                woyouService.printTextWithFont(formatLine("NOMBRE:", customerName), MONO_FONT_PATH, FONT_SIZE_NORMAL, callback);

                if (isPIX) {
                    String docCliente = (saleDayItems != null ? saleDayItems.getDocCliente() : Value.getDocCliente(context));
                    woyouService.printTextWithFont(formatLine("DOCUMENTO:", docCliente), MONO_FONT_PATH, FONT_SIZE_NORMAL, callback);
                }

                woyouService.printTextWithFont("--------------------------------", MONO_FONT_PATH, FONT_SIZE_NORMAL, callback);

                // --- VALORES ---
                if (isPIX) {
                    String valorDolar = "USD " + (saleDayItems != null ? saleDayItems.getValorDolar() : Value.getValorEmUSD(context));
                    woyouService.printTextWithFont(formatLine("VALOR EN DOLARES", valorDolar), MONO_FONT_PATH, FONT_SIZE_NORMAL, callback);
                } else {
                    String valor = (saleDayItems != null ? saleDayItems.getValorPago() : Value.getValorPago(context));
                    woyouService.printTextWithFont(formatLine("VALOR", valor), MONO_FONT_PATH, FONT_SIZE_NORMAL, callback);
                }
                woyouService.printTextWithFont("--------------------------------", MONO_FONT_PATH, FONT_SIZE_NORMAL, callback);

                // --- DETALHES PARA O CLIENTE ---
                if (isCLiente) {
                    String valorVenda = (saleDayItems != null ? Util.subValoresMonetarios(saleDayItems.getValorPago(), saleDayItems.getTaxadeServico()) : Util.subValoresMonetarios(Value.getValorPago(context), Value.getTaxadeServico(context)));
                    woyouService.printTextWithFont(formatLine("VALOR VENDA", valorVenda), MONO_FONT_PATH, FONT_SIZE_NORMAL, callback);

                    boolean hasTax = (saleDayItems != null && Util.isGreaterThanZero(saleDayItems.getTaxadeServico())) || Util.isGreaterThanZero(Value.getTaxadeServico(context));
                    if (hasTax) {
                        String taxaServico = "R$ " + (saleDayItems != null ? Util.formatToTwoDecimalPlaces(saleDayItems.getTaxadeServico()) : Util.formatToTwoDecimalPlaces(Value.getTaxadeServico(context)));
                        woyouService.printTextWithFont(formatLine("TAXA DE SERVIÇO / IOF", taxaServico), MONO_FONT_PATH, FONT_SIZE_NORMAL, callback);
                    }
                }

                String totalPago = (saleDayItems != null ? saleDayItems.getValorPago() : Value.getValorPago(context));
                woyouService.printTextWithFont(formatLine("TOTAL PAGO", totalPago), MONO_FONT_PATH, FONT_SIZE_NORMAL, callback);


                // --- RODAPÉ ---
                String numDocumento = (saleDayItems != null ? saleDayItems.getSerialNumber() : Value.getReferenciaInterna(context));
                woyouService.printTextWithFont(formatLine("Nº DOCUMENTO", numDocumento), MONO_FONT_PATH, FONT_SIZE_NORMAL, callback);

                if (isPIX) {
                    woyouService.printTextWithFont("IOF INCLUIDO\n", MONO_FONT_PATH, FONT_SIZE_NORMAL, callback);
                }

                // Finaliza a impressão, avança o papel e corta
                woyouService.lineWrap(4, callback);
                woyouService.sendRAWData(ESCUtil.cutPaper(), callback);

            } catch (RemoteException e) {
                Log.e(TAG, "Erro de comunicação ao tentar imprimir.", e);
                // Você pode querer usar um Handler para mostrar o Toast na UI thread
                // Looper.prepare(); Toast.makeText(context, "Erro ao imprimir.", Toast.LENGTH_SHORT).show(); Looper.loop();
            }
        }).start();
    }

    private static String formatLine(String leftStr, String rightStr) {
        int leftLen = leftStr.length();
        int rightLen = rightStr.length();
        int spacesNeeded = RECEIPT_WIDTH_CHARS - leftLen - rightLen;
        Log.d(TAG, "Erro de comunicação ao tentar imprimir. spacesNeeded = "+spacesNeeded);
        if (spacesNeeded <= 0) {
            // Se não houver espaço, apenas concatene com um separador
            return leftStr + " " + rightStr;

        }

        StringBuilder sb = new StringBuilder(leftStr);

        Log.d(TAG, "Erro de comunicação ao tentar imprimir. spacesNeeded = "+ sb );
        for (int i = 0; i < spacesNeeded; i++) {
            sb.append(" ");
        }
        sb.append(rightStr);
        //sb.append("\n");

        return sb.toString();
    }

    private static void showPrintDialog(Context context, String erro) {
        // Criação do AlertDialog
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Erro");
        builder.setMessage(erro);

        // Botão "Sim"
        builder.setPositiveButton("Fechar", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {

            }
        });


        // Mostrar o AlertDialog
        AlertDialog dialog = builder.create();
        dialog.show();
    }

    private static List<TextPrintLine> printList(String leftStr, String centerStr, String rightStr, int size, boolean bold) {
        TextPrintLine textPrintLine1 = new TextPrintLine(leftStr, PrintLine.LEFT, size, bold);
        TextPrintLine textPrintLine2 = new TextPrintLine(centerStr, PrintLine.CENTER, size, bold);
        TextPrintLine textPrintLine3 = new TextPrintLine(rightStr, PrintLine.RIGHT, size, bold);
        List<TextPrintLine> list = new ArrayList<>();
        list.add(textPrintLine1);
        list.add(textPrintLine2);
        list.add(textPrintLine3);
        return list;
    }

    private static List<TextPrintLine> printLine(String left, String right) {

        List<TextPrintLine> list = new ArrayList<>();

        String formatted = String.format("%-16s %16s", left, right);

        list.add(new TextPrintLine(formatted, PrintLine.LEFT, 16, false));

        return list;
    }

    public static boolean isImpressoraCompativel() {
        PrinterManager testPrinter = null;

        // Opcional: Você também pode verificar pelo modelo do hardware (Build.MODEL)
        // se souber exatamente quais são os modelos (ex: "POS-X", "SmartPOS").
        Log.d(TAG, "Verificando compatibilidade no modelo: " + Build.MODEL);

        try {
            // 1. Tenta encontrar a classe no sistema. Se for um celular comum,
            // que não tem a lib injetada no Android, pode falhar aqui.
            Class.forName("android.device.PrinterManager");

            // 2. Instancia e tenta abrir a conexão
            testPrinter = new PrinterManager();
            testPrinter.open(); // Se for "stub" (sem driver real), lança exceção aqui

            // 3. Tenta ler o status para garantir comunicação com o hardware
            int status = testPrinter.getStatus();

            // Retorna true somente se não houve exceções até aqui
            return true;

        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            Log.e(TAG, "SDK da impressora não encontrada neste dispositivo: " + e.getMessage());
            return false;
        } catch (Exception | Error e) {
            // Captura Exception e Error (como UnsatisfiedLinkError para falhas de driver C/C++)
            Log.e(TAG, "Driver da impressora ausente ou erro de stub: " + e.getMessage());
            return false;
        } finally {
            // Fecha a conexão de teste para liberar a impressora para o método de impressão real
            if (testPrinter != null) {
                try {
                    testPrinter.close();
                } catch (Exception e) {
                    Log.e(TAG, "Erro ao fechar impressora de teste: " + e.getMessage());
                }
            }
        }
    }


    public static void imprimirComprovantePOSI9100(final Context context, final SaleDayItems saleDayItems, final boolean isPIX, final boolean isCliente, int tamanhoFonte1, int tamanhoFonte2) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                PrinterManager mPrinterManager = null;
                try {
                    mPrinterManager = new PrinterManager();
                    mPrinterManager.open();

                    if (mPrinterManager.getStatus() == 0) { // 0 = OK
                        mPrinterManager.setupPage(384, -1);

                        int y = 0;
                        int lineSpacing = 12; // Respiro entre blocos de informação
                        int fontSizeBase = tamanhoFonte1;
                        int fontSizeDoc = tamanhoFonte2;
                        String fontName = "simsun";

                        // 1. CABEÇALHO (Centralizado e em Negrito)
                        // style 1 = bold, format 1 = center
                        y += mPrinterManager.drawTextEx("COMPROBANTE DE PAGO", 0, y, 384, -1, fontName, fontSizeBase, 0, 1, 1) + lineSpacing;

                        // 2. LOGO
                        try {
                            Bitmap bitmap = BitmapFactory.decodeResource(context.getResources(), R.drawable.logo_mall);
                            if (bitmap != null) {
                                int xLogo = (384 - bitmap.getWidth()) / 2;
                                mPrinterManager.drawBitmap(bitmap, xLogo, y);
                                y += bitmap.getHeight() + lineSpacing;
                            }
                        } catch (Exception e) {
                            Log.e("PRINTER", "Erro logo: " + e.getMessage());
                        }

                        // 3. IDENTIFICAÇÃO DO ESTABELECIMENTO
                        String titulo = (saleDayItems != null) ? saleDayItems.getTitle() : Value.getTypeComprov(context);
                        y += mPrinterManager.drawTextEx(titulo, 0, y, 384, -1, fontName, fontSizeBase, 0, 1, 1) + 4; // Negrito e centralizado

                        String nomeEmpresa = (saleDayItems != null) ? saleDayItems.getMerchantName() : Value.getNomeEmpresa(context);
                        y += mPrinterManager.drawTextEx(nomeEmpresa, 0, y, 384, -1, fontName, fontSizeBase, 0, 0, 1) + lineSpacing; // Centralizado

                        y += desenharDivisor(mPrinterManager, y, fontName, fontSizeBase);

                        // 4. DADOS DA TRANSAÇÃO
                        y = desenharLinhaDupla(mPrinterManager, "COD. TRANS.:", (saleDayItems != null ? saleDayItems.getCodTransacao() : Value.getCodOperacao(context)), y, fontName, fontSizeBase);

                        String dataHora = (saleDayItems != null ? saleDayItems.getData() + " " + saleDayItems.getHora() : Value.getDataHoraMovimento(context));
                        y = desenharLinhaDupla(mPrinterManager, "DATA:", dataHora, y, fontName, fontSizeBase);

                        y += desenharDivisor(mPrinterManager, y, fontName, fontSizeBase);

                        // 5. DADOS DO PAGADOR
                        String nome = (saleDayItems != null ? saleDayItems.getCustomerName() : Value.getNomePagador(context));
                        y = desenharLinhaDupla(mPrinterManager, "NOMBRE:", nome, y, fontName, fontSizeBase);

                        if (isPIX) {
                            String doc = (saleDayItems != null ? saleDayItems.getDocCliente() : Value.getDocCliente(context));
                            y = desenharLinhaDupla(mPrinterManager, "DOCUMENTO:", doc, y, fontName, fontSizeBase);
                        }

                        y += desenharDivisor(mPrinterManager, y, fontName, fontSizeBase);

                        // 6. VALORES
                        if (isPIX) {
                            String valorUsd = "USD " + (saleDayItems != null ? saleDayItems.getValorDolar() : Value.getValorEmUSD(context));
                            y = desenharLinhaDupla(mPrinterManager, "VALOR EN DOLARES", valorUsd, y, fontName, fontSizeBase);
                        } else {
                            String valorNormal = (saleDayItems != null ? saleDayItems.getValorPago() : Value.getValorPago(context));
                            y = desenharLinhaDupla(mPrinterManager, "VALOR", valorNormal, y, fontName, fontSizeBase);
                        }

                        if (isCliente) {
                            String valorVenda = (saleDayItems != null ?
                                    Util.subValoresMonetarios(saleDayItems.getValorPago(), saleDayItems.getTaxadeServico()) :
                                    Util.subValoresMonetarios(Value.getValorPago(context), Value.getTaxadeServico(context)));
                            y = desenharLinhaDupla(mPrinterManager, "VALOR VENDA", valorVenda, y, fontName, fontSizeBase);

                            boolean temTaxa = (saleDayItems != null && Util.isGreaterThanZero(saleDayItems.getTaxadeServico())) || Util.isGreaterThanZero(Value.getTaxadeServico(context));
                            if (temTaxa) {
                                String taxaStr = "R$ " + (saleDayItems != null ? Util.formatToTwoDecimalPlaces(saleDayItems.getTaxadeServico()) : Util.formatToTwoDecimalPlaces(Value.getTaxadeServico(context)));
                                y = desenharLinhaDupla(mPrinterManager, "TAXA SERV / IOF", taxaStr, y, fontName, fontSizeBase);
                            }
                        }

                        y += desenharDivisor(mPrinterManager, y, fontName, fontSizeBase);

                        // 7. TOTAL PAGO (Destacado em Negrito)
                        String totalPago = (saleDayItems != null ? saleDayItems.getValorPago() : Value.getValorPago(context));
                        y = desenharLinhaDuplaDestacada(mPrinterManager, "TOTAL PAGO", totalPago, y, fontName, fontSizeBase);

                        // 8. RODAPÉ
                        y += 20; // Espaço em branco extra antes do rodapé
                        String numDoc = (saleDayItems != null ? saleDayItems.getSerialNumber() : (Value.getReferenciaInterna(context) + ""));
                        y = desenharLinhaDupla(mPrinterManager, "Nº DOCUMENTO", numDoc, y, fontName, fontSizeDoc);

                        if (isPIX) {
                            y += mPrinterManager.drawTextEx("IOF INCLUIDO", 0, y, 384, -1, fontName, fontSizeDoc, 0, 0, 1) + 10;
                        }

                        // FINALIZAÇÃO
                        mPrinterManager.printPage(0);
                        mPrinterManager.paperFeed(64);

                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    if (mPrinterManager != null) mPrinterManager.close();
                }
            }
        }).start();
    }

    /**
     * Desenha uma linha com rótulo à esquerda e valor à direita, garantindo o alinhamento perfeito.
     */
    private static int desenharLinhaDupla(PrinterManager pm, String label, String value, int y, String font, int size) {
        if (label == null) label = "";
        if (value == null) value = "";

        int colunaLabel = 160;
        int colunaValor = 384 - colunaLabel;
        int margemInterna = 8; // Espaço entre as linhas

        // Rótulo alinhado à esquerda (format 0)
        int alturaLabel = pm.drawTextEx(label, 0, y, colunaLabel, -1, font, size, 0, 0, 0);
        // Valor alinhado à direita (format 2) começando exatamente onde o rótulo termina no eixo X
        int alturaValor = pm.drawTextEx(value, colunaLabel, y, colunaValor, -1, font, size, 0, 0, 2);

        int maiorAltura = Math.max(alturaLabel, alturaValor);
        return y + (maiorAltura > 0 ? maiorAltura : size) + margemInterna;
    }

    /**
     * Mesma lógica da linha dupla, mas aplica estilo Negrito (style 1) para o Total.
     */
    private static int desenharLinhaDuplaDestacada(PrinterManager pm, String label, String value, int y, String font, int size) {
        if (label == null) label = "";
        if (value == null) value = "";

        int colunaLabel = 160;
        int colunaValor = 384 - colunaLabel;

        int alturaLabel = pm.drawTextEx(label, 0, y, colunaLabel, -1, font, size, 0, 1, 0);
        int alturaValor = pm.drawTextEx(value, colunaLabel, y, colunaValor, -1, font, size, 0, 1, 2);

        int maiorAltura = Math.max(alturaLabel, alturaValor);
        return y + (maiorAltura > 0 ? maiorAltura : size) + 12;
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

    /**
     * Método auxiliar para padronizar as linhas tracejadas de separação.
     */
    private static int desenharDivisor(PrinterManager pm, int y, String font, int size) {
        String divisor = "--------------------------------";
        int altura = pm.drawTextEx(divisor, 0, y, 384, -1, font, size, 0, 0, 1);
        return altura + 6; // Altura da linha + respiro
    }


    public static void mostrarComprovanteAlert(Context context,
                                               SaleDayItems saleDayItems,
                                               boolean isPIX,
                                               boolean isCliente) {

        LayoutInflater inflater = LayoutInflater.from(context);
        View view = inflater.inflate(R.layout.dialog_comprovante, null);

        LinearLayout container = view.findViewById(R.id.containerComprovante);
        ScrollView scroll = view.findViewById(R.id.scrollComprovante);

        container.setScaleX(0.7f);
        container.setScaleY(0.7f);

        int fontBase = 12;
        int fontDoc = 10;

        addCenterText(context, container,
              "COMPROVANTE DE PAGO",
                fontBase + 2, true);

        // =========================
        // LOGO
        // =========================
        try {
            ImageView logo = new ImageView(context);
            logo.setImageResource(R.drawable.logo_mall);

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            params.gravity = Gravity.CENTER;

            logo.setLayoutParams(params);
            container.addView(logo);
        } catch (Exception ignored) {}

        // =========================
        // TÍTULO
        // =========================
        addCenterText(context, container,
                saleDayItems != null ? saleDayItems.getTitle() : "COMPROBANTE",
                fontBase + 2, true);

        addCenterText(context, container,
                "",
                fontBase + 2, true);
        addCenterText(context, container,
                "",
                fontBase + 2, true);

        addLeftText(context, container,
                saleDayItems != null ? saleDayItems.getMerchantName() : "EMPRESA",
                fontBase);

        // =========================
        // TRANSAÇÃO
        // =========================
        addLinha(context, container, "COD. TRANS.:",
                getSafe( (saleDayItems != null ? saleDayItems.getCodTransacao() : Value.getCodOperacao(context))),
                fontBase);

        addLinha(context, container, "DATA:",(saleDayItems != null ? saleDayItems.getData() + " " + saleDayItems.getHora() : Value.getDataHoraMovimento(context)),
                fontBase);

        addDivider(context, container);

        // =========================
        // CLIENTE
        // =========================
        String nomeOrCpf = "";
        Boolean isNome = false;

        if(saleDayItems != null && saleDayItems.getCustomerName() != null && !saleDayItems.getCustomerName().isEmpty()) {
            nomeOrCpf = saleDayItems.getCustomerName();
            isNome = true;
        }else
            nomeOrCpf = DocumentFormatter.formatCPF(saleDayItems.getCustomerCPF());


        addLinha(context, container, (isNome?"NOMBRE:":"CPF:"),getSafe(nomeOrCpf), fontBase);

        Log.i("TESTE TESTE TESTE", "isNome = "+isNome);
        Log.i("TESTE TESTE TESTE", "CustomerName = "+saleDayItems.getCustomerName());
        Log.i("TESTE TESTE TESTE", "nomeOrCpf = "+nomeOrCpf);

        if (isPIX) {
            addLinha(context, container, "DOCUMENTO:",
                    getSafe((saleDayItems != null ? saleDayItems.getDocCliente() : Value.getDocCliente(context))),
                    fontBase);
        }

        addDivider(context, container);

        // =========================
        // VALOR
        // =========================
        if (isPIX) {
            String usd = "USD " + getSafe((saleDayItems != null ? saleDayItems.getValorDolar() : Value.getValorEmUSD(context) + ""));
            addLinha(context, container, "VALOR EN DOLARES", usd, fontBase);
        } else {
            addLinha(context, container, "VALOR",
                    CurrencyDisplayFormatter.formatCurrencyWithSymbol(saleDayItems != null ? saleDayItems.getValorTransacao() : Value.getValorPago(context),
                            saleDayItems != null ? saleDayItems.getOrigem() : Value.getOriginCurrencie(context)),
                    fontBase);
        }

        addDivider(context, container);

        // =========================
        // DETALHAMENTO
        // =========================
        if (isCliente) {

            String valorPago = getSafe(saleDayItems != null ? saleDayItems.getValorPago() : "0,00");
            String taxa = getSafe(saleDayItems != null ? saleDayItems.getTaxadeServico() : "0");

            String valorVenda = Util.subValoresMonetarios(valorPago, taxa);

            addLinha(context, container, "VALOR VENDA", valorVenda, fontBase);

            if (Util.isGreaterThanZero(taxa)) {
                String taxaFormatada = "R$ " + Util.formatToTwoDecimalPlaces(taxa);
                addLinha(context, container, "TAXA SERV / IOF", taxaFormatada, fontBase);
            }
        }

        // =========================
        // TOTAL
        // =========================
        addLinha(context, container, "TOTAL PAGO",
                formatMoney(getSafe((saleDayItems != null ? saleDayItems.getValorPago() : Value.getValorPago(context)))),
                fontBase);

        // =========================
        // DOCUMENTO
        // =========================
        addLinha(context, container, "Nº DOCUMENTO",
                getSafe(saleDayItems != null ? saleDayItems.getSerialNumber() : "123456"),
                fontDoc);

        if (isPIX) {
            addLeftText(context, container, "IOF INCLUIDO", fontDoc);
        }

        // =========================
        // ALERT
        // =========================

        // Esconde o container temporariamente para ele não piscar estático na tela antes de animar
        container.setVisibility(View.INVISIBLE);

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(view)
                .setPositiveButton("OK", null)
                .create();
        dialog.show();

        // =========================
        // 🎬 ANIMAÇÃO (MÁQUINA DE IMPRESSÃO REAL)
        // =========================
        scroll.post(() -> {
            int width = container.getWidth();
            int height = container.getHeight();
            int visibleHeight = scroll.getHeight();

            // Travas de segurança
            if (width == 0 || height == 0 || visibleHeight == 0) {
                container.setVisibility(View.VISIBLE);
                return;
            }

            // Posiciona o comprovante escondido na parte de baixo do ScrollView
            container.setTranslationY(visibleHeight);
            container.setClipBounds(new android.graphics.Rect(0, 0, width, 0));
            container.setVisibility(View.VISIBLE);

            ValueAnimator animator = ValueAnimator.ofInt(0, height);
            animator.setDuration(1500); // 3 segundos deslizando
            animator.setInterpolator(new android.view.animation.LinearInterpolator());

            animator.addUpdateListener(animation -> {
                int v = (int) animation.getAnimatedValue();

                if (v <= visibleHeight) {
                    // Fase 1: O papel sai do fundo (slot) e vai subindo fisicamente na tela
                    container.setTranslationY(visibleHeight - v);
                    scroll.scrollTo(0, 0);
                } else {
                    // Fase 2: O comprovante é maior que a tela. Travamos o topo lá em cima e descemos a barra de rolagem
                    container.setTranslationY(0);
                    scroll.scrollTo(0, v - visibleHeight);
                }

                // A máscara recorta o papel revelando apenas do topo (0) até o ponto impresso (v)
                container.setClipBounds(new android.graphics.Rect(0, 0, width, v));
            });

            animator.start();
        });
    }

    private static void addLinha(Context context, LinearLayout container,
                                 String label, String value, int size) {

        LinearLayout linha = new LinearLayout(context);
        linha.setOrientation(LinearLayout.HORIZONTAL);

        TextView tvLabel = new TextView(context);
        tvLabel.setText(label);
        tvLabel.setTextSize(size);
        tvLabel.setTypeface(Typeface.MONOSPACE);

        tvLabel.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0.5f));

        TextView tvValue = new TextView(context);
        tvValue.setText(value);
        tvValue.setTextSize(size);
        tvValue.setTypeface(Typeface.MONOSPACE);
        tvValue.setGravity(Gravity.END);
        tvValue.setMaxLines(2);

        tvValue.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0.5f));

        linha.addView(tvLabel);
        linha.addView(tvValue);

        container.addView(linha);
    }

    private static void addDivider(Context context, LinearLayout container) {
        View divider = new View(context);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                3
        );

        params.setMargins(0, 8, 0, 8);
        divider.setLayoutParams(params);

        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.LINE);
        drawable.setStroke(2, Color.BLACK, 10, 6);

        divider.setBackground(drawable);

        container.addView(divider);
    }

    private static void addCenterText(Context context, LinearLayout container,
                                      String text, int size, boolean bold) {

        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextSize(size);
        tv.setGravity(Gravity.CENTER);
        tv.setTypeface(Typeface.MONOSPACE,
                bold ? Typeface.BOLD : Typeface.NORMAL);

        container.addView(tv);
    }

    private static void addLeftText(Context context, LinearLayout container,
                                    String text, int size) {

        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextSize(size);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setGravity(Gravity.START);

        container.addView(tv);
    }

    private static String getSafe(String value) {
        return (value == null || value.isEmpty()) ? "-" : value;
    }

    private static String formatMoney(String value) {
        if (value == null || value.isEmpty()) return "R$ 0,00";
        return value;
    }
}
