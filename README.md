
# SmartPOS PDV PRO 🚀

Um aplicativo de Ponto de Venda (PDV) robusto e moderno, construído nativamente para Android (Kotlin). Desenhado especificamente para rodar em Maquininhas SmartPOS (como Sunmi, Gertec, DSpread) e Tablets, focando na agilidade do comércio varejista e setor de alimentação (Food Service).

## 🌟 Principais Recursos

- **Venda Rápida & Carrinho:** Adição de produtos, controle de estoque e fluxo de checkout intuitivo.
- **Gestão de Comandas / Mesas:** Abertura de comandas, lançamento de itens, divisão de contas e fechamento flexível.
- **Bi-Monetário & Câmbio em Tempo Real:** Suporte nativo para transações em múltiplas moedas (BRL, PYG, ARS, USD, EUR) com conversão precisa, lidando perfeitamente com moedas sem casas decimais (como Guaranies).
- **Taxas Customizáveis:** Motor inteligente para aplicação de Propina e Taxa de Serviço, podendo ser isenta, percentual (%) ou valor fixo em moeda estrangeira convertida.
- **Impressão Térmica Nativa:** Integração direta com impressoras térmicas (Sunmi via AIDL) para impressão de recibos, comprovantes e fechamentos.
- **Múltiplos Meios de Pagamento:** Suporte para pagamentos em Dinheiro (com cálculo de troco cross-currency) e integração profunda com TEF/Cartões (Plug Pay).

## 🛠 Tecnologias Utilizadas

O projeto foi construído seguindo as melhores práticas da arquitetura Android nativa moderna:

- **Linguagem:** Kotlin
- **Arquitetura:** MVVM (Model-View-ViewModel) e Clean Architecture
- **Gerenciamento de Estado:** Coroutines & StateFlow
- **Injeção de Dependência:** Dagger Hilt
- **Comunicação de Rede:** Retrofit, OkHttp e Moshi/Gson
- **Integração de Hardware:** AIDL Services (Sunmi Printer, DSpread)

## 🚀 Como Executar o Projeto Localmente

### Pré-requisitos
- **Android Studio** (versão Ladybug ou superior recomendada)
- **SDK do Android** configurado (Target API 34+)

### Passos
1. Clone o repositório:
   ```bash
   git clone https://github.com/ekuhnen/SmartPOS-PDV-PRO.git
   ```
2. Abra o projeto no **Android Studio**. A pasta raiz do projeto Android é a pasta `android/`.
3. Aguarde o Gradle baixar todas as dependências e indexar o projeto.
4. Crie um emulador (AVD) ou conecte um dispositivo físico SmartPOS via cabo USB (Habilitando a Depuração USB).
5. Clique em **Run** (`Shift + F10`) no Android Studio.

> **Nota para testes de Impressão:** Emuladores padrão do Android Studio não suportam o hardware de impressão térmica Sunmi. Para testar o fluxo de impressão, certifique-se de implantar o app em um equipamento Sunmi físico.

## 📦 Estrutura do Repositório

- `/android` - Código nativo do aplicativo Android principal (Kotlin).
- `/src` - Componentes compartilhados e arquivos base de web/node.
- `*.md` - Manuais e documentações de integrações de hardware.

## 🤝 Contribuições

Este repositório é gerenciado ativamente. Relate *Issues* ou abra *Pull Requests* diretamente para sugestões, melhorias e correções de bugs.
