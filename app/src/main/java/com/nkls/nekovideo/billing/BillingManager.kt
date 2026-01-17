package com.nkls.nekovideo.billing

import android.Manifest
import android.accounts.AccountManager
import android.app.Activity
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import com.android.billingclient.api.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BillingManager(
    private val activity: Activity,
    private val premiumManager: PremiumManager
) {
    private val TAG = "BillingManager"
    private val PRODUCT_ID = "nekovideo_premium_purchase" // Compra única

    private var billingClient: BillingClient? = null

    init {
        setupBillingClient()
    }

    private fun setupBillingClient() {
        val pendingPurchasesParams = PendingPurchasesParams.newBuilder()
            .enableOneTimeProducts()
            .build()

        billingClient = BillingClient.newBuilder(activity)
            .setListener { billingResult, purchases ->
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
                    for (purchase in purchases) {
                        handlePurchase(purchase)
                    }
                }
            }
            .enablePendingPurchases(pendingPurchasesParams)
            .build()
    }

    // Chame isso no onResume da Activity
    fun restorePurchases() {
        if (billingClient == null || !billingClient!!.isReady) {
            connectToBilling()
        } else {
            queryPurchases()
        }
    }

    fun debugGoogleAccount() {
        // Verifica se tem permissão
        if (ActivityCompat.checkSelfPermission(
                activity,
                Manifest.permission.GET_ACCOUNTS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "SEM PERMISSAO GET_ACCOUNTS")

            // Pede permissão
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.GET_ACCOUNTS),
                100
            )
            return
        }

        val accountManager = AccountManager.get(activity)
        val accounts = accountManager.getAccountsByType("com.google")

        Log.d(TAG, "=== CONTAS GOOGLE NO DISPOSITIVO ===")
        Log.d(TAG, "Total de contas: ${accounts.size}")

        if (accounts.isEmpty()) {
            Log.e(TAG, "NENHUMA CONTA ENCONTRADA!")
        } else {
            accounts.forEach { account ->
                Log.d(TAG, "Conta: ${account.name}")
            }
        }

        Log.d(TAG, "VERIFIQUE: Esta conta esta nos TESTADORES DE LICENCA?")
    }

    private fun connectToBilling() {
        if (billingClient?.isReady == false &&
            billingClient?.connectionState != BillingClient.ConnectionState.CONNECTING) {

            billingClient?.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(billingResult: BillingResult) {
                    if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                        Log.d(TAG, "Billing conectado")
                        queryPurchases()
                    }
                }

                override fun onBillingServiceDisconnected() {
                    Log.w(TAG, "Billing desconectado")
                }
            })
        }
    }

    // Função para consumir compra (útil para testes)
    fun consumePurchaseForTesting() {
        billingClient?.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        ) { billingResult, purchases ->
            purchases.forEach { purchase ->
                if (purchase.products.contains(PRODUCT_ID)) {
                    Log.d(TAG, "Consumindo compra de teste...")

                    val consumeParams = ConsumeParams.newBuilder()
                        .setPurchaseToken(purchase.purchaseToken)
                        .build()

                    billingClient?.consumeAsync(consumeParams) { result, _ ->
                        if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                            Log.d(TAG, "Compra consumida!")
                            premiumManager.setPremium(false)
                            queryPurchases()
                        }
                    }
                }
            }
        }
    }

    fun queryPurchases() {
        billingClient?.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP) // Compra única
                .build()
        ) { billingResult, purchases ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                Log.d(TAG, "=== VERIFICANDO COMPRAS ===")
                Log.d(TAG, "Total: ${purchases.size}")

                purchases.forEach { purchase ->
                    Log.d(TAG, "Products: ${purchase.products}")
                    Log.d(TAG, "PurchaseState: ${purchase.purchaseState}")
                }

                val hasPremium = purchases.any {
                    it.products.contains(PRODUCT_ID) &&
                            it.purchaseState == Purchase.PurchaseState.PURCHASED
                }

                Log.d(TAG, "Premium ativo: $hasPremium")
                premiumManager.setPremium(hasPremium)
            }
        }
    }

    fun launchPurchaseFlow(onSuccess: () -> Unit = {}, onError: (String) -> Unit = {}) {
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(PRODUCT_ID)
            .setProductType(BillingClient.ProductType.INAPP) // Compra única
            .build()

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(product))
            .build()

        billingClient?.queryProductDetailsAsync(params) { billingResult, result ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                if (result.productDetailsList.isNotEmpty()) {
                    val productDetails = result.productDetailsList[0]

                    // Para compra única (INAPP), não precisa de offerToken
                    val productDetailsParams = BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(productDetails)
                        .build()

                    val billingFlowParams = BillingFlowParams.newBuilder()
                        .setProductDetailsParamsList(listOf(productDetailsParams))
                        .build()

                    billingClient?.launchBillingFlow(activity, billingFlowParams)
                } else {
                    onError("Produto nao disponivel")
                }
            } else {
                onError("Erro ao buscar produto")
            }
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
            if (!purchase.isAcknowledged) {
                acknowledgePurchase(purchase)
            }
            premiumManager.setPremium(true)
            Log.d(TAG, "Premium ativado")
        }
    }

    private fun acknowledgePurchase(purchase: Purchase) {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()

        CoroutineScope(Dispatchers.IO).launch {
            billingClient?.acknowledgePurchase(params) { billingResult ->
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.d(TAG, "Compra reconhecida")
                }
            }
        }
    }

    fun destroy() {
        billingClient?.endConnection()
    }
}
