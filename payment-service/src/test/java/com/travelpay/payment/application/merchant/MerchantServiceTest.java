package com.travelpay.payment.application.merchant;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.travelpay.common.auth.AuthRole;
import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.payment.application.merchant.client.MerchantLedgerClient;
import com.travelpay.payment.application.merchant.client.MerchantOwnerClient;
import com.travelpay.payment.application.merchant.dto.CreateMerchantCommand;
import com.travelpay.payment.application.merchant.dto.MerchantBalanceResult;
import com.travelpay.payment.application.merchant.dto.MerchantResult;
import com.travelpay.payment.domain.merchant.Merchant;
import com.travelpay.payment.domain.merchant.MerchantRepository;
import com.travelpay.payment.domain.merchant.MerchantStatus;
import com.travelpay.payment.domain.payment.CurrencyCode;
import com.travelpay.payment.domain.payment.PaymentErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MerchantServiceTest {

    private static final UUID MERCHANT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OWNER_USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String NAME = "여행 카페";
    private static final Instant CREATED_AT = Instant.parse("2026-10-31T00:12:34.567Z");
    private static final CreateMerchantCommand COMMAND = new CreateMerchantCommand(NAME, CurrencyCode.KRW, OWNER_USER_ID);
    private static final AuthenticatedUser MASTER = new AuthenticatedUser(
            UUID.fromString("99999999-9999-9999-9999-999999999999"), AuthRole.MASTER);
    private static final AuthenticatedUser OWNER = new AuthenticatedUser(OWNER_USER_ID, AuthRole.USER);
    private static final AuthenticatedUser OTHER_USER = new AuthenticatedUser(
            UUID.fromString("33333333-3333-3333-3333-333333333333"), AuthRole.USER);

    private MerchantRepository merchantRepository;
    private MerchantLedgerClient ledgerClient;
    private MerchantOwnerClient ownerClient;
    private MerchantService merchantService;

    @BeforeEach
    void setUp() {
        merchantRepository = mock(MerchantRepository.class);
        ledgerClient = mock(MerchantLedgerClient.class);
        ownerClient = mock(MerchantOwnerClient.class);
        when(ownerClient.isActiveUser(OWNER_USER_ID)).thenReturn(true);
        merchantService = new MerchantService(new MerchantTransactionSteps(merchantRepository), ledgerClient,
                ownerClient);
    }

    @Test
    void createsLedgerAccountAndActivatesNewMerchant() {
        Merchant merchant = givenSavedMerchant();
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(merchant));

        MerchantResult result = merchantService.createMerchant(COMMAND);

        verify(ledgerClient).openAccounts(MERCHANT_ID, CurrencyCode.KRW);
        assertEquals(MERCHANT_ID, result.merchantId());
        assertEquals(MerchantStatus.ACTIVE, result.status());
        assertEquals(OWNER_USER_ID, result.ownerUserId());
        assertEquals(MERCHANT_ID, merchant.getLedgerWalletId());
    }

    @Test
    void returnsPendingMerchantWhenLedgerFails() {
        Merchant merchant = givenSavedMerchant();
        doThrow(new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE))
                .when(ledgerClient).openAccounts(MERCHANT_ID, CurrencyCode.KRW);

        MerchantResult result = merchantService.createMerchant(COMMAND);

        assertEquals(MERCHANT_ID, result.merchantId());
        assertEquals(MerchantStatus.PENDING, result.status());
        verify(merchantRepository, never()).findByIdForUpdate(any());
        assertNull(merchant.getLedgerWalletId());
    }

    @Test
    void rejectsMissingOrInactiveOwnerWithoutSaving() {
        when(ownerClient.isActiveUser(OWNER_USER_ID)).thenReturn(false);

        var exception = assertThrows(BusinessException.class, () -> merchantService.createMerchant(COMMAND));

        assertSame(PaymentErrorCode.MERCHANT_OWNER_NOT_FOUND, exception.getErrorCode());
        verifyNoInteractions(merchantRepository, ledgerClient);
    }

    @Test
    void reportsUserServiceFailureWithoutSaving() {
        when(ownerClient.isActiveUser(OWNER_USER_ID))
                .thenThrow(new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE));

        var exception = assertThrows(BusinessException.class, () -> merchantService.createMerchant(COMMAND));

        assertSame(CommonErrorCode.SERVICE_UNAVAILABLE, exception.getErrorCode());
        verifyNoInteractions(merchantRepository, ledgerClient);
    }

    @Test
    void retryActivatesPendingMerchant() {
        Merchant merchant = merchant(MerchantStatus.PENDING);
        when(merchantRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(merchant));
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(merchant));

        MerchantResult result = merchantService.retryLedgerAccount(MERCHANT_ID);

        verify(ledgerClient).openAccounts(MERCHANT_ID, CurrencyCode.KRW);
        assertEquals(MerchantStatus.ACTIVE, result.status());
        assertEquals(MERCHANT_ID, merchant.getLedgerWalletId());
    }

    @Test
    void retryKeepsPendingAndReportsLedgerFailure() {
        Merchant merchant = merchant(MerchantStatus.PENDING);
        when(merchantRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(merchant));
        doThrow(new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE))
                .when(ledgerClient).openAccounts(MERCHANT_ID, CurrencyCode.KRW);

        var exception = assertThrows(BusinessException.class, () -> merchantService.retryLedgerAccount(MERCHANT_ID));

        assertSame(CommonErrorCode.SERVICE_UNAVAILABLE, exception.getErrorCode());
        assertEquals(MerchantStatus.PENDING, merchant.getStatus());
    }

    @Test
    void retryReturnsActiveMerchantWithoutCallingLedger() {
        when(merchantRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(merchant(MerchantStatus.ACTIVE)));

        MerchantResult result = merchantService.retryLedgerAccount(MERCHANT_ID);

        verifyNoInteractions(ledgerClient);
        assertEquals(MerchantStatus.ACTIVE, result.status());
    }

    @Test
    void retryDoesNotActivateTwiceWhenAnotherRequestActivatedFirst() {
        Merchant activated = merchant(MerchantStatus.ACTIVE);
        when(merchantRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(merchant(MerchantStatus.PENDING)));
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(activated));

        MerchantResult result = merchantService.retryLedgerAccount(MERCHANT_ID);

        assertEquals(MerchantStatus.ACTIVE, result.status());
        assertNull(activated.getLedgerWalletId());
    }

    @Test
    void retryRejectsUnknownMerchant() {
        when(merchantRepository.findById(MERCHANT_ID)).thenReturn(Optional.empty());

        var exception = assertThrows(BusinessException.class, () -> merchantService.retryLedgerAccount(MERCHANT_ID));

        assertSame(PaymentErrorCode.MERCHANT_NOT_FOUND, exception.getErrorCode());
        verifyNoInteractions(ledgerClient);
    }

    @Test
    void masterSeesBalanceOfAnyMerchant() {
        givenMerchant(activeMerchant());
        when(ledgerClient.getBalance(MERCHANT_ID, CurrencyCode.KRW)).thenReturn(15000L);

        MerchantBalanceResult result = merchantService.getBalance(MASTER, MERCHANT_ID);

        assertEquals(MERCHANT_ID, result.merchantId());
        assertEquals(CurrencyCode.KRW, result.currency());
        assertEquals(15000L, result.balance());
    }

    @Test
    void ownerSeesBalanceOfOwnMerchant() {
        givenMerchant(activeMerchant());
        when(ledgerClient.getBalance(MERCHANT_ID, CurrencyCode.KRW)).thenReturn(15000L);

        MerchantBalanceResult result = merchantService.getBalance(OWNER, MERCHANT_ID);

        assertEquals(15000L, result.balance());
    }

    @Test
    void suspendedMerchantBalanceIsStillVisible() {
        Merchant merchant = activeMerchant();
        ReflectionTestUtils.setField(merchant, "status", MerchantStatus.SUSPENDED);
        givenMerchant(merchant);
        when(ledgerClient.getBalance(MERCHANT_ID, CurrencyCode.KRW)).thenReturn(700L);

        assertEquals(700L, merchantService.getBalance(OWNER, MERCHANT_ID).balance());
    }

    @Test
    void otherUserGetsNotFoundForSomeoneElsesMerchant() {
        givenMerchant(activeMerchant());

        var exception = assertThrows(BusinessException.class,
                () -> merchantService.getBalance(OTHER_USER, MERCHANT_ID));

        assertSame(PaymentErrorCode.MERCHANT_NOT_FOUND, exception.getErrorCode());
        verifyNoInteractions(ledgerClient);
    }

    @Test
    void balanceOfUnknownMerchantIsNotFound() {
        when(merchantRepository.findById(MERCHANT_ID)).thenReturn(Optional.empty());

        var exception = assertThrows(BusinessException.class, () -> merchantService.getBalance(MASTER, MERCHANT_ID));

        assertSame(PaymentErrorCode.MERCHANT_NOT_FOUND, exception.getErrorCode());
        verifyNoInteractions(ledgerClient);
    }

    @Test
    void pendingMerchantHasNoBalanceYet() {
        givenMerchant(merchant(MerchantStatus.PENDING));

        var exception = assertThrows(BusinessException.class, () -> merchantService.getBalance(OWNER, MERCHANT_ID));

        assertSame(PaymentErrorCode.MERCHANT_NOT_ACTIVE, exception.getErrorCode());
        verifyNoInteractions(ledgerClient);
    }

    @Test
    void balanceReportsLedgerFailure() {
        givenMerchant(activeMerchant());
        when(ledgerClient.getBalance(MERCHANT_ID, CurrencyCode.KRW))
                .thenThrow(new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE));

        var exception = assertThrows(BusinessException.class, () -> merchantService.getBalance(MASTER, MERCHANT_ID));

        assertSame(CommonErrorCode.SERVICE_UNAVAILABLE, exception.getErrorCode());
    }

    private void givenMerchant(Merchant merchant) {
        when(merchantRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(merchant));
    }

    private static Merchant activeMerchant() {
        Merchant merchant = merchant(MerchantStatus.ACTIVE);
        ReflectionTestUtils.setField(merchant, "ledgerWalletId", MERCHANT_ID);
        return merchant;
    }

    private Merchant givenSavedMerchant() {
        Merchant merchant = merchant(MerchantStatus.PENDING);
        when(merchantRepository.save(any(Merchant.class))).thenReturn(merchant);
        return merchant;
    }

    private static Merchant merchant(MerchantStatus status) {
        Merchant merchant = Merchant.create(NAME, CurrencyCode.KRW, OWNER_USER_ID);
        ReflectionTestUtils.setField(merchant, "id", MERCHANT_ID);
        ReflectionTestUtils.setField(merchant, "status", status);
        ReflectionTestUtils.setField(merchant, "createdAt", CREATED_AT);
        return merchant;
    }
}
