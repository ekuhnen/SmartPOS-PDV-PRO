package com.plugpdv.pdv.hardware.printer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import android.widget.Toast;

import com.plugpdv.pdv.R;
import com.pos.sdk.printer.POIPrinterManager;
import com.pos.sdk.printer.models.BitmapPrintLine;
import com.pos.sdk.printer.models.PrintLine;
import com.pos.sdk.printer.models.TextPrintLine;

public class PrinterUtil8 {

    private static final String TAG = "PrinterUtil8";
    private static final int RECEIPT_WIDTH_CHARS = 32;

    public static void printReceipt(final Context context, final ReceiptData data) {
        new Thread(() -> {
            try {
                final POIPrinterManager pm = new POIPrinterManager(context);
                pm.open();
                pm.cleanCache();
                pm.setPrintGray(3000);
                pm.setLineSpace(3);

                // 1) Título
                pm.addPrintLine(new TextPrintLine(data.getTitle(), PrintLine.CENTER, 24, true));

                // 2) Logo
                try {
                    Bitmap logo = BitmapFactory.decodeResource(context.getResources(), R.drawable.logo_plug);
                    if (logo != null) {
                        pm.addPrintLine(new BitmapPrintLine(logo, PrintLine.CENTER));
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Erro ao processar logo Kozen: " + e.getMessage());
                }

                // 3) Nome do Estabelecimento
                if (!data.getMerchantName().isEmpty()) {
                    pm.addPrintLine(new TextPrintLine(data.getMerchantName(), PrintLine.CENTER, 20, false));
                }

                pm.addPrintLine(new TextPrintLine("--------------------------------", PrintLine.CENTER, 20, false));

                final Context ctx = com.plugpdv.pdv.utils.LanguageManager.updateResources(context, com.plugpdv.pdv.utils.LanguageManager.getLanguage(context));

                // 4) Detalhes da Transação
                pm.addPrintLine(formatKozenLine(ctx.getString(R.string.print_transaction_label), data.getTransactionId()));
                pm.addPrintLine(formatKozenLine(ctx.getString(R.string.print_date_label), data.getDate() + " " + data.getTime()));
                if (!data.getOperatorName().isEmpty()) {
                    pm.addPrintLine(formatKozenLine(ctx.getString(R.string.print_operator_label), data.getOperatorName()));
                }

                pm.addPrintLine(new TextPrintLine("--------------------------------", PrintLine.CENTER, 20, false));

                // 5) Dados do Cliente
                if (!data.getCustomerName().isEmpty()) {
                    pm.addPrintLine(formatKozenLine(ctx.getString(R.string.print_customer_label), data.getCustomerName()));
                }
                if (!data.getCustomerDocument().isEmpty()) {
                    pm.addPrintLine(formatKozenLine(ctx.getString(R.string.print_doc_label), data.getCustomerDocument()));
                }
                if (!data.getCustomerName().isEmpty() || !data.getCustomerDocument().isEmpty()) {
                    pm.addPrintLine(new TextPrintLine("--------------------------------", PrintLine.CENTER, 20, false));
                }

                // 6) Forma e Total
                pm.addPrintLine(formatKozenLine(ctx.getString(R.string.print_payment_method_label), data.getPaymentMethod()));
                
                if (data.getServiceFeeAmount() != null && !data.getServiceFeeAmount().equals("0,00")) {
                    pm.addPrintLine(formatKozenLine(ctx.getString(R.string.print_service_fee_label), data.getCurrency() + " " + data.getServiceFeeAmount()));
                }
                
                pm.addPrintLine(new TextPrintLine(ctx.getString(R.string.print_total_label) + " " + data.getCurrency() + " " + data.getAmount(), PrintLine.RIGHT, 24, true));

                pm.addPrintLine(new TextPrintLine("--------------------------------", PrintLine.CENTER, 20, false));

                // 7) Rodapé
                if (!data.getSerialNumber().isEmpty()) {
                    pm.addPrintLine(formatKozenLine(ctx.getString(R.string.print_sn_label), data.getSerialNumber()));
                }
                pm.addPrintLine(new TextPrintLine(ctx.getString(R.string.print_thank_you), PrintLine.CENTER, 18, false));

                // Avanco de papel
                for (int i = 0; i < 4; i++) {
                    pm.addPrintLine(new TextPrintLine(" ", PrintLine.LEFT, 16, false));
                }

                pm.beginPrint(new POIPrinterManager.IPrinterListener() {
                    @Override
                    public void onStart() {}

                    @Override
                    public void onFinish() {
                        try { pm.close(); } catch (Exception e) {}
                    }

                    @Override
                    public void onError(int code, String msg) {
                        showToast(context, "Erro na impressão (Kozen POI): " + msg + " code: " + code);
                        try { pm.close(); } catch (Exception e) {}
                    }
                });

            } catch (Exception e) {
                showToast(context, "Erro ao imprimir: " + e.getMessage());
                Log.e(TAG, "Printer Exception", e);
            }
        }).start();
    }

    private static TextPrintLine formatKozenLine(String left, String right) {
        int spaces = RECEIPT_WIDTH_CHARS - left.length() - right.length();
        StringBuilder sb = new StringBuilder(left);
        for (int i = 0; i < Math.max(1, spaces); i++) {
            sb.append(" ");
        }
        sb.append(right);
        return new TextPrintLine(sb.toString(), PrintLine.LEFT, 20, false);
    }

    public static boolean isImpressoraCompativel() {
        return true;
    }

    private static void showToast(final Context context, final String message) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        );
    }
}
