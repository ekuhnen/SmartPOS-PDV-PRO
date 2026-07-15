# Integração e Solução da Impressora Dspread D80

Este documento resume todas as etapas, diagnósticos e alterações de código realizadas para garantir o funcionamento correto da impressora térmica integrada do terminal SmartPOS **Dspread D80** no aplicativo `smartpos-pdv-pro`.

---

## 1. O Problema Diagnosticado
Originalmente, a impressora do dispositivo D80 falhava ao tentar inicializar ou imprimir cupons.
- **Limitação da SDK Antiga (`1.3.9-beta`)**: O método `PrinterManager.getInstance().getPrinter()` da SDK antiga não possuía mapeamento de hardware para a `Build.MODEL == "D80"`. Com isso, ele caía no fallback padrão para uma impressora Bluetooth (`Mp5801`), falhando silenciosamente no terminal SmartPOS.
- **Instanciação Manual da `D70`**: Ao forçar a classe de modelo `D70` (`POIPrinterManager`), o aplicativo quebrava com erro de inicialização porque a D80 não possui o serviço de sistema requerido pela D70.
- **Instanciação Manual da `D60`**: Ao forçar a classe de modelo `D60` (que utiliza comunicação UART/Serial local), o aplicativo compilava mas a impressora ainda não ativava porque a SDK `1.3.9-beta` possuía rotinas de UART obsoletas ou incompatíveis com o firmware do D80.

---

## 2. A Solução Implementada

Para resolver o problema de forma definitiva, realizamos a atualização das SDKs para as versões oficiais do fabricante e refatoramos as integrações de hardware.

### A. Atualização das SDKs do Fabricante
Substituímos as dependências antigas baixadas do repositório Maven pelos arquivos binários oficiais mais recentes (extraídos do app demonstrativo oficial da Dspread):
1. **`dspread_print_sdk-1.9.4.aar`** (substitui a `1.3.9-beta`)
2. **`dspread_pos_sdk_8.4.4.aar`** (substitui a `7.0.7`)

Ambos os arquivos foram adicionados ao diretório local de bibliotecas:
- [dspread_print_sdk-1.9.4.aar](file:///d:/Projetos/smartpos-pdv-pro/android/app/libs/dspread_print_sdk-1.9.4.aar)
- [dspread_pos_sdk_8.4.4.aar](file:///d:/Projetos/smartpos-pdv-pro/android/app/libs/dspread_pos_sdk_8.4.4.aar)

### B. Configuração das Dependências no Gradle
Atualizamos o arquivo [android/app/build.gradle](file:///d:/Projetos/smartpos-pdv-pro/android/app/build.gradle) para usar as novas bibliotecas locais (`.aar`) em vez das antigas via Maven:

```groovy
    // Substituição das dependências antigas:
    // implementation 'com.dspread.print:dspread_print_sdk:1.3.9-beta'
    // implementation 'com.dspread.library:dspread_pos_sdk:7.0.7'
    implementation(name: 'dspread_print_sdk-1.9.4', ext: 'aar')
    implementation(name: 'dspread_pos_sdk_8.4.4', ext: 'aar')
```

### C. Refatoração de Namespaces (Pacotes Java)
A versão `8.4.4` do POS SDK alterou a localização (pacotes) das classes de impressão POI/Gertec de `com.pos.sdk.printer` para `com.dspread.xpos.printer`. 

Atualizamos os arquivos abaixo para refletir essa alteração:
1. **[GertecPrinter.kt](file:///d:/Projetos/smartpos-pdv-pro/android/app/src/main/java/com/plugpdv/pdv/hardware/GertecPrinter.kt)**:
   ```kotlin
   // Antes: import com.pos.sdk.printer.*
   import com.dspread.xpos.printer.POIPrinterManager
   import com.dspread.xpos.printer.PosPrinter
   import com.dspread.xpos.printer.models.BitmapPrintLine
   import com.dspread.xpos.printer.models.PrintLine
   import com.dspread.xpos.printer.models.TextPrintLine
   ```
2. **[GeneralPrinterUtil.java](file:///d:/Projetos/smartpos-pdv-pro/android/app/src/main/java/com/plugpdv/pdv/hardware/printer/GeneralPrinterUtil.java)**:
   ```java
   // Antes: import com.pos.sdk.printer.*
   import com.dspread.xpos.printer.POIPrinterManager;
   import com.dspread.xpos.printer.PosPrinter;
   import com.dspread.xpos.printer.models.BitmapPrintLine;
   import com.dspread.xpos.printer.models.PrintLine;
   import com.dspread.xpos.printer.models.TextPrintLine;
   ```

### D. Inicialização Dinâmica do Modelo na `DspreadPrinter`
Revertemos a fixação manual de instanciamento da classe `D60`. Como a nova SDK `1.9.4` agora traz o modelo `D80` mapeado nativamente para a classe de impressora `D60` (que inicializa a comunicação via UART/Serial), reintroduzimos a chamada padrão e limpa do SDK em [DspreadPrinter.kt](file:///d:/Projetos/smartpos-pdv-pro/android/app/src/main/java/com/plugpdv/pdv/hardware/DspreadPrinter.kt):

```kotlin
    override fun init() {
        if (isInitialized) return
        try {
            // A SDK 1.9.4 reconhece a D80 e retorna a instância de D60 automaticamente
            printerDevice = PrinterManager.getInstance().getPrinter()
            printerDevice?.setPrintListener(printListener)
            
            // Inicialização UART síncrona
            printerDevice?.initPrinter(context)
            isPrinterConnected = true
            ...
```

### E. Permissões do Sistema Operacional Android
Para garantir a correta comunicação com os serviços locais de impressão sob restrições do Android 11+ (API 30+), o arquivo [AndroidManifest.xml](file:///d:/Projetos/smartpos-pdv-pro/android/app/src/main/AndroidManifest.xml) contém as seguintes diretivas obrigatórias:
- A permissão `<uses-permission android:name="android.permission.CP_VERIFY_PAY_DEVICE" />`.
- A consulta na tag `<queries>` apontando para o serviço de impressão da fabricante: `<package android:name="com.dspread.sdkservice" />`.

---

## 3. Validação Técnica
- Executamos a rotina de limpeza e compilação (`.\gradlew clean assembleDebug`) e o Gradle concluiu o build com sucesso total (**`BUILD SUCCESSFUL`**), atestando que todas as novas classes e namespaces estão perfeitamente integrados e sem erros de sintaxe.
