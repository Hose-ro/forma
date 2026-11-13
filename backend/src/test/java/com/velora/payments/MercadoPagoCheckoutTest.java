package com.velora.payments;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.velora.payments.infrastructure.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MercadoPagoCheckoutTest {
    private final ObjectMapper json=new ObjectMapper();
    @Test void choosesOfficialInitPointAndRejectsLegacySandboxAndUntrustedRedirects() {
        var client=new MercadoPagoClient(null,json);
        var preference=json.createObjectNode();
        String url="https://www.mercadopago.com.mx/checkout/v1/redirect?pref_id=123-test";
        preference.put("init_point",url);
        preference.put("sandbox_init_point","https://sandbox.mercadopago.com.mx/checkout/v1/redirect?pref_id=123-test");
        assertThat(client.checkoutUrl(preference)).isEqualTo(url);
        for(String invalid:new String[]{"https://sandbox.mercadopago.com.mx/checkout/v1/redirect", "http://www.mercadopago.com.mx/checkout/v1/redirect",
            "https://www.mercadopago.com.mx.evil.example/checkout/v1/redirect","https://evil.example/checkout/v1/redirect",
            "https://user@www.mercadopago.com.mx/checkout/v1/redirect","https://www.mercadopago.com.mx:8443/checkout/v1/redirect",
            "https://www.mercadopago.com.mx/other","https://www.mercadopago.com.mx/checkout/v1/redirect#fragment"}) {
            preference.put("init_point",invalid);
            assertThatThrownBy(()->client.checkoutUrl(preference)).isInstanceOf(MercadoPagoClient.ProviderException.class);
        }
        preference.remove("init_point");
        assertThatThrownBy(()->client.checkoutUrl(preference)).isInstanceOf(MercadoPagoClient.ProviderException.class);
    }
    @Test void requiresMatchingMexicanTestSellerRatherThanInferringFromTokenPrefix() {
        var seller=json.createObjectNode();seller.put("id",123);seller.put("site_id","MLM");seller.putArray("tags").add("test_user");
        assertThat(MercadoPagoClient.testSellerMatches(seller,"123")).isTrue();
        assertThat(MercadoPagoClient.testSellerMatches(seller,"456")).isFalse();
        seller.put("site_id","MLA");assertThat(MercadoPagoClient.testSellerMatches(seller,"123")).isFalse();
        seller.put("site_id","MLM");seller.putArray("tags").add("normal");assertThat(MercadoPagoClient.testSellerMatches(seller,"123")).isFalse();
    }
    @Test void verifiesProviderIssuedBuyerIdentityAndRejectsRealOrMismatchedAccounts() {
        var buyer=json.createObjectNode();buyer.put("id",456);buyer.put("site_id","MLM");buyer.put("nickname","TESTUSER123456789");
        var payer=json.createObjectNode();payer.put("id",456);payer.put("email","test_user_123@testuser.com");
        // Public test-buyer profiles can legitimately omit the tags field.
        assertThat(MercadoPagoClient.testBuyerMatches(buyer,payer)).isTrue();
        payer.put("email","customer@example.com");assertThat(MercadoPagoClient.testBuyerMatches(buyer,payer)).isFalse();
        payer.put("email","test_user_123@testuser.com.evil.example");assertThat(MercadoPagoClient.testBuyerMatches(buyer,payer)).isFalse();
        payer.put("email","test_user_123@testuser.com");buyer.put("nickname","REALUSER");assertThat(MercadoPagoClient.testBuyerMatches(buyer,payer)).isFalse();
        buyer.putArray("tags").add("test_user");assertThat(MercadoPagoClient.testBuyerMatches(buyer,payer)).isTrue();
        buyer.put("site_id","MLA");assertThat(MercadoPagoClient.testBuyerMatches(buyer,payer)).isFalse();
        buyer.put("site_id","MLM");payer.put("id",999);assertThat(MercadoPagoClient.testBuyerMatches(buyer,payer)).isFalse();
    }

}
