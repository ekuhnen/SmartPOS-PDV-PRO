# Guia de Integração Multi-Impressora (Sunmi, Kozen P8, Urovo, iMin)

Este guia detalha como portar a lógica de impressão universal do PlugPay para o seu projeto de PDV. O sistema é projetado para detectar automaticamente o hardware e usar o driver correto.

## 1. Arquivos e Pastas para Copiar

Para que a impressão funcione em todos os dispositivos, você deve copiar os seguintes componentes para as pastas de destino no seu projeto **smartpos-pdv-pro**:

### A. Lógica Java (Drivers)
- **Origem:** `app/src/main/java/com/br/plugpay/printer/` (Projeto PlugPay)
- **Destino no PDV:** `D:\Projetos\smartpos-pdv-pro\src\main\printer`
- **O que copiar:** Copie toda a pasta `printer/` para o diretório de destino. Ela contém as classes `PrinterUtil` (1 a 8) e `ESCUtil`.

### B. Descritores de Interface (AIDL)
Necessário para dispositivos Sunmi e alguns modelos Kozen compatíveis.
- **Origem:** `app/src/main/aidl/woyou/aidlservice/jiuiv5/` (Projeto PlugPay)
- **Destino no PDV:** `D:\Projetos\smartpos-pdv-pro\src\main\aidl\woyou\aidlservice\jiuiv5` (Mantenha exatamente esta estrutura de pacotes).

### C. Binários do SDK (JAR)
Necessário para dispositivos Kozen (P8/P8 NEO) e Urovo que usam o `PrinterManager`.
- **Origem:** `app/libs/platform_sdk_v4.1.0326.jar` (Projeto PlugPay)
- **Destino no PDV:** `D:\Projetos\smartpos-pdv-pro\src\lib\platform_sdk_v4.1.0326.jar`

---

## 2. Configuração do `build.gradle` (App do PDV)

Certifique-se de que seu arquivo de configuração suporte AIDL e as dependências de imagem:

```gradle
android {
    buildFeatures {
        aidl = true
    }
}

dependencies {
    // SDK Sunmi
    implementation 'com.sunmi:printerlibrary:1.0.24'
    
    // Processamento de QR Codes e Imagens
    implementation 'com.google.zxing:core:3.5.3'
    
    // Importar o JAR da Kozen/Urovo a partir da pasta lib do seu projeto
    implementation files('src/lib/platform_sdk_v4.1.0326.jar')
}
```

---

## 3. Configuração do `AndroidManifest.xml`

Adicione as permissões de hardware e a declaração de visibilidade de pacotes:

```xml
<!-- Permissões de Impressão (Cobrem Sunmi, Kozen, Urovo e iMin) -->
<uses-permission android:name="com.sunmi.permission.PRINTER" />
<uses-permission android:name="com.pos.permission.PRINTER" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />

<queries>
    <!-- Essencial para Android 11+ detectar o serviço de impressão -->
    <package android:name="woyou.aidlservice.jiuiv5" />
</queries>
```

---

## 4. Como Usar no Código (Lógica de Seleção)

A lógica recomendada para disparar a impressão independente do modelo de aparelho é:

```java
String fabricante = android.os.Build.MANUFACTURER;

// 1. Tentar detectar se o driver do PrinterManager (Kozen/Urovo) está presente
if (PrinterUtil.isImpressoraCompativel()) {
    // Usa o driver para Kozen P8 / P8 NEO / Urovo
    PrinterUtil8.imprimirComprovantePOSI9100(context, dados, isPIX, isCliente, 22, 18);
} 
// 2. Se for Sunmi ou o driver acima não for encontrado, tenta via AIDL
else {
    // Onde 'woyouService' é a instância obtida via bindService
    PrinterUtil.printReceipt(woyouService, context, dados, isPIX, isCliente);
}
```

## 5. Resumo das Classes Utilitárias

| Classe | Especialidade |
| :--- | :--- |
| `PrinterUtil.java` | Driver principal para **Sunmi** (via AIDL). |
| `PrinterUtil8.java` | Driver para **Kozen P8 / NEO** e **Urovo** (via PrinterManager). |
| `ESCUtil.java` | Comandos brutos para formatação (negrito, corte de papel, alinhamento). |
| `POIPrinterManager` | SDK genérico para outros terminais SmartPOS. |
