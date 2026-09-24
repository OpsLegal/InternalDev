package com.opslegal.tda.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import com.opslegal.tda.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Google Play subscription. One product ([PRODUCT_ID]) with two base plans, "monthly"
 * and "yearly", configured in the Play Console. The table and widget are free; the AI
 * assistant and the daily AI review need an active subscription.
 */
class BillingRepository(context: Context, private val scope: CoroutineScope) : PurchasesUpdatedListener {

    data class Offer(val basePlanId: String, val price: String, val details: ProductDetails, val offerToken: String)

    private val client = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    // Debug builds are unlocked so the assistant can be tested without a Play listing.
    private val premiumState = MutableStateFlow(BuildConfig.DEBUG)
    val premium: StateFlow<Boolean> = premiumState.asStateFlow()

    private val offersState = MutableStateFlow<List<Offer>>(emptyList())
    val offers: StateFlow<List<Offer>> = offersState.asStateFlow()

    fun connect() {
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) scope.launch { refresh() }
            }

            override fun onBillingServiceDisconnected() = Unit
        })
    }

    suspend fun refresh() {
        if (!client.isReady) return
        val purchases = client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build(),
        )
        handle(purchases.purchasesList)

        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(PRODUCT_ID)
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        val result = client.queryProductDetails(QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build())
        offersState.value = result.productDetailsList.orEmpty().flatMap { details ->
            details.subscriptionOfferDetails.orEmpty().map { offer ->
                Offer(
                    basePlanId = offer.basePlanId,
                    price = offer.pricingPhases.pricingPhaseList.last().formattedPrice,
                    details = details,
                    offerToken = offer.offerToken,
                )
            }
        }.distinctBy { it.basePlanId }
    }

    fun buy(activity: Activity, offer: Offer) {
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(offer.details)
                        .setOfferToken(offer.offerToken)
                        .build(),
                ),
            )
            .build()
        client.launchBillingFlow(activity, params)
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            scope.launch { handle(purchases) }
        }
    }

    private suspend fun handle(purchases: List<Purchase>) {
        val active = purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        premiumState.value = BuildConfig.DEBUG || active.isNotEmpty()
        // Unacknowledged purchases are refunded by Play after 3 days.
        active.filter { !it.isAcknowledged }.forEach {
            client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(it.purchaseToken).build())
        }
    }

    companion object {
        const val PRODUCT_ID = "tda_premium"
    }
}
