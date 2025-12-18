package com.nkls.nekovideo.billing

import android.app.Activity
import android.util.Log
import com.android.billingclient.api.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BillingManager(
    private val activity: Activity,
    private val premiumManager: PremiumManager
) {
    private val TAG = "BillingManager"
    private val PRODUCT_ID = "nekovideo_premium_purchase"

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
                } else if (billingResult.responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
                    Log.d(TAG, "Item já possui")
                    premiumManager.setPremium(true)
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

    private fun connectToBilling() {
        if (billingClient?.isReady == false &&
            billingClient?.connectionState != BillingClient.ConnectionState.CONNECTING) {

            billingClient?.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(billingResult: BillingResult) {
                    if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                        Log.d(TAG, "Billing conectado")
                        queryPurchases() // Apenas verifica compras, não busca detalhes do produto
                    }
                }

                override fun onBillingServiceDisconnected() {
                    Log.w(TAG, "Billing desconectado")
                }
            })
        }
    }

    // IMPORTANTE: Só chame queryProductDetailsAsync quando o usuário clicar no botão
    fun launchPurchaseFlow(onSuccess: () -> Unit = {}, onError: (String) -> Unit = {}) {
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(PRODUCT_ID)
            .setProductType(BillingClient.ProductType.INAPP)
            .build()

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(product))
            .build()

        billingClient?.queryProductDetailsAsync(params) { billingResult, queryProductDetailsResult ->
            Log.d(TAG, "Response code: ${billingResult.responseCode}")
            Log.d(TAG, "Debug message: ${billingResult.debugMessage}")

            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                val productDetailsList = queryProductDetailsResult.productDetailsList
                Log.d(TAG, "Produtos encontrados: ${productDetailsList.size}")

                if (productDetailsList.isNotEmpty()) {
                    val productDetails = productDetailsList[0]
                    Log.d(TAG, "Produto: ${productDetails.name} - ${productDetails.productId}")

                    val productDetailsParamsList = listOf(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(productDetails)
                            .build()
                    )

                    val billingFlowParams = BillingFlowParams.newBuilder()
                        .setProductDetailsParamsList(productDetailsParamsList)
                        .build()

                    billingClient?.launchBillingFlow(activity, billingFlowParams)
                } else {
                    Log.e(TAG, "Lista vazia")
                    onError("Produto não disponível")
                }
            } else {
                Log.e(TAG, "Erro: ${billingResult.debugMessage}")
                onError("Produto não disponível")
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

    fun queryPurchases() {
        billingClient?.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        ) { billingResult, purchases ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                val hasPremium = purchases.any {
                    it.products.contains(PRODUCT_ID) &&
                            it.purchaseState == Purchase.PurchaseState.PURCHASED
                }
                premiumManager.setPremium(hasPremium)
                Log.d(TAG, "Status premium: $hasPremium")
            }
        }
    }

    fun destroy() {
        billingClient?.endConnection()
    }
}