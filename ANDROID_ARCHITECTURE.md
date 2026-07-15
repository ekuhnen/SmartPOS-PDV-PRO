/**
 * Sugestão de Arquitetura Android (Kotlin) para SmartPOS
 * 
 * Padrão: MVVM (Model-View-ViewModel) com Clean Architecture
 * 
 * Camadas:
 * 1. Data: Repositories, APIs (Retrofit), Local DB (Room)
 * 2. Domain: UseCases, Models (Business Logic)
 * 3. UI: Activities/Fragments (Compose), ViewModels
 */

/*
// --- Exemplo de API Service (Retrofit) ---
interface PosApiService {
    @POST("auth-login")
    suspend fun login(@Body request: LoginRequest): Response<AuthResponse>

    @GET("api-catalogs")
    suspend fun getCatalogs(@Header("Authorization") token: String): Response<CatalogResponse>

    @POST("api-vendas")
    suspend fun registerSale(
        @Header("Authorization") token: String,
        @Body sale: SaleRequest
    ): Response<SaleResponse>
}

// --- Exemplo de ViewModel (Venda Direta) ---
class SaleViewModel(private val repository: SaleRepository) : ViewModel() {
    private val _cart = MutableStateFlow<List<CartItem>>(emptyList())
    val cart = _cart.asStateFlow()

    fun addToCart(product: Product) {
        // Lógica de incremento ou adição
    }

    fun checkout() {
        viewModelScope.launch {
            repository.sendSale(_cart.value)
        }
    }
}

// --- Lógica de Impressão Esc/POS (Conceitual) ---
class PrinterService(private val printer: ThermalPrinter) {
    fun printReceipt(sale: Sale) {
        printer.apply {
            setAlignment(CENTER)
            setTextSize(LARGE)
            printText("MINHA EMPRESA PDV\n")
            setTextSize(NORMAL)
            printText("CNPJ: 00.000.000/0001-00\n")
            printText("--------------------------------\n")
            setAlignment(LEFT)
            sale.items.forEach { item ->
                printText("${item.name}\n")
                printText("${item.qty} x ${item.price} = ${item.total}\n")
            }
            printText("--------------------------------\n")
            setAlignment(RIGHT)
            printText("TOTAL: ${sale.total}\n")
            feed(3)
            cutPaper()
        }
    }
}
*/
