```
smartpos-pdv-pro
├── .env.example
├── .gitignore
├── .vscode
│   └── settings.json
├── ANDROID_ARCHITECTURE.md
├── D80_PRINTER_INTEGRATION.md
├── DEEPLINK_PAYMENT_FLOW.md
├── DEEPLINK_RETURNS.md
├── MULTI_PRINTER_INTEGRATION.md
├── README.md
├── android
│   ├── .idea
│   │   ├── .gitignore
│   │   ├── .name
│   │   ├── AndroidProjectSystem.xml
│   │   ├── assetWizardSettings.xml
│   │   ├── caches
│   │   │   └── deviceStreaming.xml
│   │   ├── codeStyles
│   │   │   ├── Project.xml
│   │   │   └── codeStyleConfig.xml
│   │   ├── compiler.xml
│   │   ├── deploymentTargetSelector.xml
│   │   ├── gradle.xml
│   │   ├── jarRepositories.xml
│   │   ├── kotlinc.xml
│   │   ├── migrations.xml
│   │   ├── misc.xml
│   │   ├── runConfigurations.xml
│   │   ├── vcs.xml
│   │   └── workspace.xml
│   ├── api_debug.cjs
│   ├── api_test.cjs
│   ├── app
│   │   ├── build.gradle
│   │   ├── google-services.json
│   │   ├── libs
│   │   │   ├── FinancialLib_1.5.0_release.aar
│   │   │   ├── cfr.jar
│   │   │   ├── classes-2.1.17.jar
│   │   │   ├── com
│   │   │   │   └── action
│   │   │   │       └── printerservice
│   │   │   │           └── ActionPrinter.class
│   │   │   ├── decompiled_dspread.java
│   │   │   ├── dspread_pos_sdk_8.4.4.aar
│   │   │   ├── dspread_print_sdk-1.9.4.aar
│   │   │   ├── dspread_print_sdk_fixed.aar
│   │   │   └── platform_sdk_v4.1.0326.jar
│   │   ├── release
│   │   │   └── app-release.aab
│   │   └── src
│   │       ├── main
│   │       │   ├── AndroidManifest.xml
│   │       │   ├── aidl
│   │       │   │   ├── com
│   │       │   │   │   └── sunmi
│   │       │   │   │       └── trans
│   │       │   │   │           └── TransBean.aidl
│   │       │   │   └── woyou
│   │       │   │       └── aidlservice
│   │       │   │           └── jiuiv5
│   │       │   │               ├── ICallback.aidl
│   │       │   │               ├── ITax.aidl
│   │       │   │               └── IWoyouService.aidl
│   │       │   ├── ic_launcher-playstore.png
│   │       │   ├── java
│   │       │   │   └── com
│   │       │   │       ├── plugpdv
│   │       │   │       │   └── pdv
│   │       │   │       │       ├── PlugPdvApplication.kt
│   │       │   │       │       ├── api
│   │       │   │       │       │   ├── BlockResponseInterceptor.kt
│   │       │   │       │       │   ├── DeviceIdInterceptor.kt
│   │       │   │       │       │   └── PosApiService.kt
│   │       │   │       │       ├── database
│   │       │   │       │       │   ├── AppDatabase.kt
│   │       │   │       │       │   ├── CatalogDao.kt
│   │       │   │       │       │   ├── LocalSaleDao.kt
│   │       │   │       │       │   ├── LocalSaleEntity.kt
│   │       │   │       │       │   ├── TaxDao.kt
│   │       │   │       │       │   └── TaxEntity.kt
│   │       │   │       │       ├── di
│   │       │   │       │       │   ├── DatabaseModule.kt
│   │       │   │       │       │   ├── NetworkModule.kt
│   │       │   │       │       │   └── SupabaseModule.kt
│   │       │   │       │       ├── hardware
│   │       │   │       │       │   ├── DejavooPrinter.kt
│   │       │   │       │       │   ├── DspreadPrinter.kt
│   │       │   │       │       │   ├── GertecPrinter.kt
│   │       │   │       │       │   ├── HardwareFactory.kt
│   │       │   │       │       │   ├── KozenPrinter.kt
│   │       │   │       │       │   ├── Printer.kt
│   │       │   │       │       │   ├── ScanCallback.kt
│   │       │   │       │       │   ├── Scanner.kt
│   │       │   │       │       │   ├── SunmiPrinter.kt
│   │       │   │       │       │   ├── SunmiScanner.kt
│   │       │   │       │       │   └── printer
│   │       │   │       │       │       ├── ESCUtil.java
│   │       │   │       │       │       ├── GeneralPrinterUtil.java
│   │       │   │       │       │       ├── PrinterUtil8.java
│   │       │   │       │       │       └── ReceiptData.java
│   │       │   │       │       ├── models
│   │       │   │       │       │   ├── AuthResponse.kt
│   │       │   │       │       │   ├── CashierHistoryResponse.kt
│   │       │   │       │       │   ├── CashierRequest.kt
│   │       │   │       │       │   ├── CashierSession.kt
│   │       │   │       │       │   ├── CatalogInfo.kt
│   │       │   │       │       │   ├── CatalogItem.kt
│   │       │   │       │       │   ├── CatalogResponse.kt
│   │       │   │       │       │   ├── ComandaDetailResponse.kt
│   │       │   │       │       │   ├── ComandasListResponse.kt
│   │       │   │       │       │   ├── Command.kt
│   │       │   │       │       │   ├── CommandActionRequest.kt
│   │       │   │       │       │   ├── ExchangeRequest.kt
│   │       │   │       │       │   ├── ExchangeResponse.kt
│   │       │   │       │       │   ├── LoginRequest.kt
│   │       │   │       │       │   ├── PaymentMethodSummary.kt
│   │       │   │       │       │   ├── Permission.kt
│   │       │   │       │       │   ├── Product.kt
│   │       │   │       │       │   ├── RestaurantResponse.kt
│   │       │   │       │       │   ├── SaleHistoryItem.kt
│   │       │   │       │       │   ├── SaleItem.kt
│   │       │   │       │       │   ├── SaleRequest.kt
│   │       │   │       │       │   ├── SaleResponse.kt
│   │       │   │       │       │   ├── SalesHistoryResponse.kt
│   │       │   │       │       │   ├── ServiceFeeConfig.kt
│   │       │   │       │       │   ├── Table.kt
│   │       │   │       │       │   ├── TableItem.kt
│   │       │   │       │       │   ├── TaxRate.kt
│   │       │   │       │       │   ├── TaxResponse.kt
│   │       │   │       │       │   ├── User.kt
│   │       │   │       │       │   └── UserPermissions.kt
│   │       │   │       │       ├── repository
│   │       │   │       │       │   ├── CatalogRepository.kt
│   │       │   │       │       │   └── TaxRepository.kt
│   │       │   │       │       ├── service
│   │       │   │       │       │   ├── DeviceGuardService.kt
│   │       │   │       │       │   └── MyFirebaseMessagingService.kt
│   │       │   │       │       ├── ui
│   │       │   │       │       │   ├── BaseActivity.kt
│   │       │   │       │       │   ├── auth
│   │       │   │       │       │   │   ├── AuthViewModel.kt
│   │       │   │       │       │   │   └── LoginActivity.kt
│   │       │   │       │       │   ├── cashier
│   │       │   │       │       │   │   ├── CashierActivity.kt
│   │       │   │       │       │   │   └── CashierViewModel.kt
│   │       │   │       │       │   ├── dashboard
│   │       │   │       │       │   │   ├── OperatorDashboardActivity.kt
│   │       │   │       │       │   │   ├── OperatorDashboardViewModel.kt
│   │       │   │       │       │   │   ├── PaymentMethodAdapter.kt
│   │       │   │       │       │   │   └── SaleHistoryAdapter.kt
│   │       │   │       │       │   └── sale
│   │       │   │       │       │       ├── CartAdapter.kt
│   │       │   │       │       │       ├── CartBottomSheet.java Cory Cory
│   │       │   │       │       │       │   └── tmp
│   │       │   │       │       │       │       └── api_test.js Cory Cory Cory Cory Cory Cory Cory
│   │       │   │       │       │       │           └── tmp
│   │       │   │       │       │       │               └── api_test.js Cory Cory
│   │       │   │       │       │       │                   └── tmp
│   │       │   │       │       │       │                       └── api_test.js Cory Cory Cory
│   │       │   │       │       │       │                           └── tmp
│   │       │   │       │       │       ├── CartBottomSheet.kt
│   │       │   │       │       │       ├── CategoryAdapter.kt
│   │       │   │       │       │       ├── CheckoutActivity.kt
│   │       │   │       │       │       ├── CheckoutItemsBottomSheet.kt
│   │       │   │       │       │       ├── CheckoutProductAdapter.kt
│   │       │   │       │       │       ├── CheckoutViewModel.kt
│   │       │   │       │       │       ├── ComandaFragment.kt
│   │       │   │       │       │       ├── CommandOrderActivity.kt
│   │       │   │       │       │       ├── CommandViewModel.kt
│   │       │   │       │       │       ├── DirectCheckoutViewModel.kt
│   │       │   │       │       │       ├── DirectSaleActivity.kt
│   │       │   │       │       │       ├── FacturaElectronicaDialog.kt
│   │       │   │       │       │       ├── MesaFragment.kt
│   │       │   │       │       │       ├── MesaViewModel.kt
│   │       │   │       │       │       ├── PaymentHandlerActivity.kt
│   │       │   │       │       │       ├── PaymentMethodSelectorBottomSheet.kt
│   │       │   │       │       │       ├── ProductAdapter.kt
│   │       │   │       │       │       ├── SalePagerAdapter.kt
│   │       │   │       │       │       ├── SaleViewModel.kt
│   │       │   │       │       │       ├── SectorAdapter.kt
│   │       │   │       │       │       ├── ServiceFeeOverrideBottomSheet.kt
│   │       │   │       │       │       ├── TableAdapter.kt
│   │       │   │       │       │       ├── TableCheckoutBottomSheet.kt
│   │       │   │       │       │       ├── TableHistoryBottomSheet.kt
│   │       │   │       │       │       ├── TableOrderActivity.kt
│   │       │   │       │       │       ├── TableOrderItemAdapter.kt
│   │       │   │       │       │       ├── TableOrderViewModel.kt
│   │       │   │       │       │       └── VendaRapidaFragment.kt
│   │       │   │       │       └── utils
│   │       │   │       │           ├── CommandManager.kt
│   │       │   │       │           ├── Constants.kt
│   │       │   │       │           ├── CrashReportActivity.kt
│   │       │   │       │           ├── CurrencyManager.kt
│   │       │   │       │           ├── DeviceIdProvider.kt
│   │       │   │       │           ├── ForceLogoutBus.kt
│   │       │   │       │           ├── GlobalCrashHandler.kt
│   │       │   │       │           ├── KillSwitchManager.kt
│   │       │   │       │           ├── LanguageManager.kt
│   │       │   │       │           ├── NetworkUtils.kt
│   │       │   │       │           ├── PaymentHelper.kt
│   │       │   │       │           ├── PaymentResultStore.kt
│   │       │   │       │           ├── PrinterHelper.kt
│   │       │   │       │           ├── ServiceFeeManager.kt
│   │       │   │       │           ├── TableManager.kt
│   │       │   │       │           └── TransferQueueManager.kt
│   │       │   │       └── sunmi
│   │       │   │           └── trans
│   │       │   │               └── TransBean.java
│   │       │   └── res
│   │       │       ├── anim
│   │       │       │   ├── item_animation_fall_down.xml
│   │       │       │   └── layout_animation_fall_down.xml
│   │       │       ├── drawable
│   │       │       │   ├── bg_bottom_sheet.xml
│   │       │       │   ├── bg_cart_count.xml
│   │       │       │   ├── bg_cart_sheet.xml
│   │       │       │   ├── bg_category_selected.xml
│   │       │       │   ├── bg_category_unselected.xml
│   │       │       │   ├── bg_circle_orange.xml
│   │       │       │   ├── bg_dashboard_gradient.xml
│   │       │       │   ├── bg_notification_badge.xml
│   │       │       │   ├── bg_orange_badge.xml
│   │       │       │   ├── bg_quantity_selector.xml
│   │       │       │   ├── bg_quantity_selector_modern.xml
│   │       │       │   ├── bg_search_bar.xml
│   │       │       │   ├── bg_stock_chip.xml
│   │       │       │   ├── bg_table_badge.xml
│   │       │       │   ├── bg_toggle_group.xml
│   │       │       │   ├── ic_add_circle_modern.xml
│   │       │       │   ├── ic_arrow_back_modern.xml
│   │       │       │   ├── ic_attach_money.xml
│   │       │       │   ├── ic_attach_money_modern.xml
│   │       │       │   ├── ic_calculate_modern.xml
│   │       │       │   ├── ic_calculator.xml
│   │       │       │   ├── ic_cashier_open_modern.xml
│   │       │       │   ├── ic_chevron_down_modern.xml
│   │       │       │   ├── ic_chevron_up_modern.xml
│   │       │       │   ├── ic_credit_card.xml
│   │       │       │   ├── ic_currency_exchange.xml
│   │       │       │   ├── ic_dashboard_modern.xml
│   │       │       │   ├── ic_delete.xml
│   │       │       │   ├── ic_delete_modern.xml
│   │       │       │   ├── ic_email_modern.xml
│   │       │       │   ├── ic_history_modern.xml
│   │       │       │   ├── ic_info_modern.xml
│   │       │       │   ├── ic_launcher_background.xml
│   │       │       │   ├── ic_lock_modern.xml
│   │       │       │   ├── ic_minus.xml
│   │       │       │   ├── ic_minus_modern.xml
│   │       │       │   ├── ic_payments_modern.xml
│   │       │       │   ├── ic_placeholder_product.xml
│   │       │       │   ├── ic_plus.xml
│   │       │       │   ├── ic_plus_modern.xml
│   │       │       │   ├── ic_print_modern.xml
│   │       │       │   ├── ic_printer.xml
│   │       │       │   ├── ic_remove_circle_modern.xml
│   │       │       │   ├── ic_sangria_modern.xml
│   │       │       │   ├── ic_scan_modern.xml
│   │       │       │   ├── ic_search_modern.xml
│   │       │       │   ├── ic_shopping_cart.xml
│   │       │       │   ├── ic_stat_push.png
│   │       │       │   ├── ic_transfer_modern.xml
│   │       │       │   └── logo_plug.png
│   │       │       ├── layout
│   │       │       │   ├── activity_cashier.xml
│   │       │       │   ├── activity_checkout.xml
│   │       │       │   ├── activity_command_order.xml
│   │       │       │   ├── activity_direct_sale.xml
│   │       │       │   ├── activity_login.xml
│   │       │       │   ├── activity_operator_dashboard.xml
│   │       │       │   ├── activity_table_order.xml
│   │       │       │   ├── dialog_factura_electronica.xml
│   │       │       │   ├── dialog_open_comanda.xml
│   │       │       │   ├── dialog_open_table.xml
│   │       │       │   ├── fragment_comanda.xml
│   │       │       │   ├── fragment_mesa.xml
│   │       │       │   ├── fragment_venda_rapida.xml
│   │       │       │   ├── item_cart.xml
│   │       │       │   ├── item_category.xml
│   │       │       │   ├── item_checkout_product.xml
│   │       │       │   ├── item_checkout_split.xml
│   │       │       │   ├── item_payment_method_tile.xml
│   │       │       │   ├── item_product.xml
│   │       │       │   ├── item_sale_history.xml
│   │       │       │   ├── item_table.xml
│   │       │       │   ├── item_table_history_log.xml
│   │       │       │   ├── item_table_order_item.xml
│   │       │       │   ├── item_tax_row.xml
│   │       │       │   ├── layout_cart_bottom_sheet.xml
│   │       │       │   ├── layout_cart_sheet.xml
│   │       │       │   ├── layout_checkout_items_sheet.xml
│   │       │       │   ├── layout_loading_overlay.xml
│   │       │       │   ├── layout_payment_method_selector.xml
│   │       │       │   ├── layout_service_fee_override.xml
│   │       │       │   ├── layout_table_checkout.xml
│   │       │       │   └── layout_table_history.xml
│   │       │       ├── menu
│   │       │       │   ├── menu_dashboard.xml
│   │       │       │   └── menu_table_order.xml
│   │       │       ├── mipmap-anydpi-v26
│   │       │       │   ├── ic_launcher.xml
│   │       │       │   └── ic_launcher_round.xml
│   │       │       ├── mipmap-hdpi
│   │       │       │   ├── ic_launcher.png
│   │       │       │   ├── ic_launcher_foreground.png
│   │       │       │   └── ic_launcher_round.png
│   │       │       ├── mipmap-mdpi
│   │       │       │   ├── ic_launcher.png
│   │       │       │   ├── ic_launcher_foreground.png
│   │       │       │   └── ic_launcher_round.png
│   │       │       ├── mipmap-xhdpi
│   │       │       │   ├── ic_launcher.png
│   │       │       │   ├── ic_launcher_foreground.png
│   │       │       │   └── ic_launcher_round.png
│   │       │       ├── mipmap-xxhdpi
│   │       │       │   ├── ic_launcher.png
│   │       │       │   ├── ic_launcher_foreground.png
│   │       │       │   └── ic_launcher_round.png
│   │       │       ├── mipmap-xxxhdpi
│   │       │       │   ├── ic_launcher.png
│   │       │       │   ├── ic_launcher_foreground.png
│   │       │       │   └── ic_launcher_round.png
│   │       │       ├── raw
│   │       │       │   ├── gtsr1.pem
│   │       │       │   ├── gtsr4.pem
│   │       │       │   └── isrgrootx1.pem
│   │       │       ├── values
│   │       │       │   ├── colors.xml
│   │       │       │   ├── dimens.xml
│   │       │       │   ├── strings.xml
│   │       │       │   └── themes.xml
│   │       │       ├── values-en
│   │       │       │   └── strings.xml
│   │       │       ├── values-es
│   │       │       │   └── strings.xml
│   │       │       └── xml
│   │       │           └── network_security_config.xml
│   │       └── res
│   │           └── layout
│   │               └── layout_payment_method_selector.xml
│   ├── build.gradle
│   ├── build_instructions.txt
│   ├── gradle
│   │   ├── gradle
│   │   │   └── wrapper
│   │   │       ├── gradle-wrapper.jar
│   │   │       └── gradle-wrapper.properties
│   │   └── wrapper
│   │       ├── gradle-wrapper.jar
│   │       └── gradle-wrapper.properties
│   ├── gradle.properties
│   ├── gradlew
│   ├── gradlew.bat
│   ├── java_pid19788.hprof
│   ├── java_pid21328.hprof
│   ├── java_pid28348.hprof
│   ├── local.properties
│   ├── login_retry.cjs
│   ├── login_retry_v2.cjs
│   ├── settings.gradle
│   ├── test_add_item.cjs
│   ├── test_cancel.cjs
│   ├── test_comanda.cjs
│   └── test_open_table.cjs
├── api-comandas.md
├── api-comandas.pdf
├── api-service-fee-update.md
├── deeplink_uri_reference.md
├── gradle.properties
├── iconapp2.png
├── index.html
├── metadata.json
├── package-lock.json
├── package.json
├── plano-killswitch-app-pdv.md
├── plugpdv-release-key.jks
├── private_key.pepk
├── project_structure.md
├── src
│   ├── App.tsx
│   ├── components
│   │   └── ComandaDetail.tsx
│   ├── context
│   │   └── AuthContext.tsx
│   ├── index.css
│   ├── lib
│   │   ├── platform_sdk_v4.1.0326.jar
│   │   ├── utils.ts
│   │   └── waitForSifenCdc.ts
│   ├── main
│   │   ├── aidl
│   │   │   ├── com
│   │   │   │   └── sunmi
│   │   │   │       └── trans
│   │   │   │           └── TransBean.aidl
│   │   │   └── woyou
│   │   │       └── aidlservice
│   │   │           └── jiuiv5
│   │   │               ├── ICallback.aidl
│   │   │               ├── ITax.aidl
│   │   │               └── IWoyouService.aidl
│   │   └── printer
│   │       ├── ESCUtil.java
│   │       ├── PrinterUtil.java
│   │       ├── PrinterUtil2.java
│   │       ├── PrinterUtil3.java
│   │       ├── PrinterUtil4.java
│   │       ├── PrinterUtil5.java
│   │       ├── PrinterUtil6.java
│   │       ├── PrinterUtil7.java
│   │       └── PrinterUtil8.java
│   ├── main.tsx
│   ├── services
│   │   ├── api.ts
│   │   └── geminiService.ts
│   ├── types.ts
│   └── views
│       ├── CashierView.tsx
│       ├── DashboardView.tsx
│       ├── DirectSaleView.tsx
│       ├── LoginView.tsx
│       └── WaiterModeView.tsx
├── temp_print.md
├── temp_setup.md
├── tree_gen.py
├── tsconfig.json
└── vite.config.ts
```
