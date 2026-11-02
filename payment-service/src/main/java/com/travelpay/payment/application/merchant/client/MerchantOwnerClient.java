package com.travelpay.payment.application.merchant.client;

import java.util.UUID;

public interface MerchantOwnerClient {

    boolean isActiveUser(UUID userId);
}
