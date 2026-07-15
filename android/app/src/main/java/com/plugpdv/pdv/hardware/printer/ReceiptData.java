package com.plugpdv.pdv.hardware.printer;

/**
 * Modelo genérico para dados de impressão de recibo, 
 * desacoplando a lógica de hardware dos modelos da aplicação.
 */
public class ReceiptData {
    private String title;
    private String merchantName;
    private String operatorName;
    private String transactionId;
    private String date;
    private String time;
    private String amount;
    private String currency;
    private String customerName;
    private String customerDocument;
    private String serialNumber;
    private boolean isPix;
    private String paymentMethod;
    private String status;
    private String serviceFeeAmount;

    public ReceiptData() {}

    // Getters e Setters
    public String getTitle() { return title != null ? title : "COMPROVANTE"; }
    public void setTitle(String title) { this.title = title; }

    public String getMerchantName() { return merchantName != null ? merchantName : ""; }
    public void setMerchantName(String merchantName) { this.merchantName = merchantName; }

    public String getOperatorName() { return operatorName != null ? operatorName : ""; }
    public void setOperatorName(String operatorName) { this.operatorName = operatorName; }

    public String getTransactionId() { return transactionId != null ? transactionId : ""; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }

    public String getDate() { return date != null ? date : ""; }
    public void setDate(String date) { this.date = date; }

    public String getTime() { return time != null ? time : ""; }
    public void setTime(String time) { this.time = time; }

    public String getAmount() { return amount != null ? amount : "0,00"; }
    public void setAmount(String amount) { this.amount = amount; }

    public String getCurrency() { return currency != null ? currency : "BRL"; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getCustomerName() { return customerName != null ? customerName : ""; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }

    public String getCustomerDocument() { return customerDocument != null ? customerDocument : ""; }
    public void setCustomerDocument(String customerDocument) { this.customerDocument = customerDocument; }

    public String getSerialNumber() { return serialNumber != null ? serialNumber : ""; }
    public void setSerialNumber(String serialNumber) { this.serialNumber = serialNumber; }

    public boolean isPix() { return isPix; }
    public void setPix(boolean pix) { isPix = pix; }

    public String getPaymentMethod() { return paymentMethod != null ? paymentMethod : ""; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }

    public String getStatus() { return status != null ? status : "APROVADO"; }
    public void setStatus(String status) { this.status = status; }

    public String getServiceFeeAmount() { return serviceFeeAmount != null ? serviceFeeAmount : "0,00"; }
    public void setServiceFeeAmount(String serviceFeeAmount) { this.serviceFeeAmount = serviceFeeAmount; }
}
