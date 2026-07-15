package com.plugpdv.pdv.hardware.printer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.RemoteException;
import android.util.Log;
import android.widget.Toast;

import com.plugpdv.pdv.R;
import com.dspread.xpos.printer.POIPrinterManager;
import com.dspread.xpos.printer.PosPrinter;
import com.dspread.xpos.printer.models.BitmapPrintLine;
import com.dspread.xpos.printer.models.PrintLine;
import com.dspread.xpos.printer.models.TextPrintLine;

import java.util.ArrayList;
import java.util.List;

import woyou.aidlservice.jiuiv5.ICallback;
import woyou.aidlservice.jiuiv5.IWoyouService;

/**
 * Utilitário geral de impressão para Sunmi (AIDL) e terminais POI (Gertec/Genéricos).
 * Adaptado para o projeto SmartPOS PDV Pro usando ReceiptData.
 */
public class GeneralPrinterUtil {
    private static final String TAG = "GeneralPrinterUtil";
    private static final int RECEIPT_WIDTH_CHARS = 32;

    // --- POI/GERTEC PRINTING ---

    public static void printPOIReceipt(Context context, ReceiptData data) {
        new Thread(() -> {
            try {
                final POIPrinterManager printerManager = new POIPrinterManager(context);
                printerManager.open();
                printerManager.cleanCache();
                printerManager.setPrintGray(3000);
                printerManager.setLineSpace(3);

                // 1) Cabeçalho
                printerManager.addPrintLine(new TextPrintLine(data.getTitle(), PrintLine.CENTER, 22, true));
                
                // 2) Logo
                Bitmap logo = BitmapFactory.decodeResource(context.getResources(), R.drawable.logo_plug);
                if (logo != null) {
                    printerManager.addPrintLine(new BitmapPrintLine(logo, PrintLine.CENTER));
                }

                // 3) Corpo
                printerManager.addPrintLine(new TextPrintLine(data.getMerchantName(), PrintLine.CENTER, 18, false));
                printerManager.addPrintLine(new TextPrintLine("--------------------------------", PrintLine.CENTER));

                printerManager.addPrintLine(formatPOILine("TRANSACAO:", data.getTransactionId()));
                printerManager.addPrintLine(formatPOILine("DATA:", data.getDate() + " " + data.getTime()));
                
                if (!data.getCustomerName().isEmpty()) {
                    printerManager.addPrintLine(formatPOILine("CLIENTE:", data.getCustomerName()));
                }

                printerManager.addPrintLine(new TextPrintLine("--------------------------------", PrintLine.CENTER));

                printerManager.addPrintLine(formatPOILine("FORMA:", data.getPaymentMethod()));
                printerManager.addPrintLine(new TextPrintLine("TOTAL: " + data.getCurrency() + " " + data.getAmount(), PrintLine.RIGHT, 20, true));

                printerManager.addPrintLine(new TextPrintLine("--------------------------------", PrintLine.CENTER));
                printerManager.addPrintLine(new TextPrintLine("OBRIGADO PELA PREFERENCIA", PrintLine.CENTER, 16, false));
                
                printerManager.addPrintLine(new TextPrintLine(" ", 0, 80)); // Feed

                printerManager.beginPrint(new POIPrinterManager.IPrinterListener() {
                    @Override public void onStart() {}
                    @Override public void onFinish() { printerManager.close(); }
                    @Override public void onError(int code, String msg) { printerManager.close(); }
                });

            } catch (Exception e) {
                Log.e(TAG, "Erro na impressão POI/Gertec: " + e.getMessage());
            }
        }).start();
    }

    private static TextPrintLine formatPOILine(String left, String right) {
        String formatted = String.format("%-14s %17s", left, right);
        return new TextPrintLine(formatted, PrintLine.LEFT, 16, false);
    }

    // --- SUNMI AIDL PRINTING ---

    public static void printSunmiAidlReceipt(IWoyouService service, Context context, ReceiptData data) {
        if (service == null) return;

        new Thread(() -> {
            try {
                ICallback.Stub callback = new ICallback.Stub() {
                    @Override public void onRunResult(boolean isSuccess) {}
                    @Override public void onReturnString(String result) {}
                    @Override public void onRaiseException(int code, String msg) {}
                    @Override public void onPrintResult(int code, String msg) {}
                };

                service.printerInit(callback);
                service.setAlignment(1, callback); // Center

                // 1) Título
                service.printTextWithFont(data.getTitle() + "\n", null, 24, callback);

                // 2) Logo
                Bitmap logo = BitmapFactory.decodeResource(context.getResources(), R.drawable.logo_plug);
                if (logo != null) {
                    service.printBitmap(logo, callback);
                }

                // 3) Merchant
                service.printTextWithFont(data.getMerchantName() + "\n", null, 20, callback);
                service.printTextWithFont("--------------------------------\n", null, 20, callback);

                // 4) Body
                service.setAlignment(0, callback); // Left
                service.printTextWithFont(formatAidlLine("TRANS:", data.getTransactionId()), null, 20, callback);
                service.printTextWithFont(formatAidlLine("DATA:", data.getDate()), null, 20, callback);
                
                service.printTextWithFont("--------------------------------\n", null, 20, callback);
                service.printTextWithFont(formatAidlLine("TOTAL:", data.getCurrency() + " " + data.getAmount()), null, 24, callback);
                
                service.setAlignment(1, callback);
                service.printTextWithFont("\nOBRIGADO PELA PREFERENCIA\n", null, 18, callback);

                service.lineWrap(4, callback);
                service.sendRAWData(ESCUtil.cutPaper(), callback);

            } catch (RemoteException e) {
                Log.e(TAG, "Erro Sunmi AIDL: " + e.getMessage());
            }
        }).start();
    }

    private static String formatAidlLine(String left, String right) {
        int spaces = RECEIPT_WIDTH_CHARS - left.length() - right.length();
        StringBuilder sb = new StringBuilder(left);
        for (int i = 0; i < Math.max(1, spaces); i++) sb.append(" ");
        sb.append(right).append("\n");
        return sb.toString();
    }
}
