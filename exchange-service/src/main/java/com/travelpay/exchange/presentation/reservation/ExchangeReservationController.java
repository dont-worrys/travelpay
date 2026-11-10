package com.travelpay.exchange.presentation.reservation;

import java.util.UUID;

import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.auth.web.CurrentUser;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.common.response.PageResponse;
import com.travelpay.exchange.application.reservation.ExchangeReservationService;
import com.travelpay.exchange.domain.reservation.ReservationStatus;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 예약 환전 API입니다. 목표 환율에 닿으면 자동으로 환전 주문을 만듭니다(체결은 환율 수집 뒤 서버가 처리). */
@RestController
@RequestMapping("/api/v1/exchange-reservations")
@RequiredArgsConstructor
public class ExchangeReservationController {

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final ExchangeReservationService reservationService;

    /**
     * 예약합니다. 예약할 때는 돈이 움직이지 않습니다.
     * 같은 Idempotency-Key·같은 내용으로 다시 보내면 새로 만들지 않고 처음 예약을 같은 응답(201)으로 돌려줍니다.
     * 헤더가 없으면 형식 오류와 같은 EXCHANGE_INVALID_IDEMPOTENCY_KEY(400)입니다(환전 신청과 같음).
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ExchangeReservationResponse> reserve(
            @CurrentUser AuthenticatedUser user,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody ExchangeReservationRequest request) {
        return ApiResponse.success(ExchangeReservationResponse.from(
                reservationService.reserve(request.toCommand(user.userId(), idempotencyKey))));
    }

    /**
     * 본인 예약 목록을 예약 최신순으로 조회합니다. status는 대문자 값(예: WAITING)이고, 없으면 전체입니다.
     * 모르는 값이거나 page가 음수, size가 1보다 작으면 COMMON_INVALID_REQUEST(400)이고, size는 최대 100으로 줄여 조회합니다.
     */
    @GetMapping
    public ApiResponse<PageResponse<ExchangeReservationSummaryResponse>> list(
            @CurrentUser AuthenticatedUser user,
            @RequestParam(required = false) ReservationStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(PageResponse.from(
                reservationService.list(user.userId(), status, page, size), ExchangeReservationSummaryResponse::from));
    }

    /** 본인 예약을 조회합니다. 체결됐으면 주문 ID와 주문 상태를 함께 내려줍니다. 다른 회원의 예약은 없는 예약과 같게 404입니다. */
    @GetMapping("/{reservationId}")
    public ApiResponse<ExchangeReservationResponse> get(@CurrentUser AuthenticatedUser user,
                                                        @PathVariable UUID reservationId) {
        return ApiResponse.success(ExchangeReservationResponse.from(reservationService.find(user.userId(), reservationId)));
    }

    /** 대기 중인 예약을 취소합니다. 이미 체결·취소·만료됐으면 409 EXCHANGE_RESERVATION_NOT_WAITING입니다. */
    @PostMapping("/{reservationId}/cancel")
    public ApiResponse<ExchangeReservationResponse> cancel(@CurrentUser AuthenticatedUser user,
                                                           @PathVariable UUID reservationId) {
        return ApiResponse.success(ExchangeReservationResponse.from(reservationService.cancel(user.userId(), reservationId)));
    }
}
