package com.travelpay.exchange.domain.order;

import java.util.Objects;
import java.util.UUID;

import com.travelpay.exchange.domain.shared.BaseCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * 환전 주문의 상태 변경 이력입니다. INSERT만 하고 수정하지 않습니다.
 * 주문 상태가 실제로 바뀐 경우에만 남깁니다. 동시에 같은 결과를 반영하려다 진 쪽은 이력을 남기지 않습니다.
 */
@Entity
@Immutable
@Table(name = "exchange_order_status_histories")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExchangeOrderStatusHistory extends BaseCreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "history_id", updatable = false)
    private UUID id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 20, updatable = false)
    private ExchangeOrderStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", length = 20, nullable = false, updatable = false)
    private ExchangeOrderStatus toStatus;

    @Column(name = "reason", length = 50, updatable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "changed_by_type", length = 20, nullable = false, updatable = false)
    private ChangedByType changedByType;

    private ExchangeOrderStatusHistory(UUID orderId, ExchangeOrderStatus fromStatus, ExchangeOrderStatus toStatus,
                                       String reason, ChangedByType changedByType) {
        this.orderId = orderId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.reason = reason;
        this.changedByType = changedByType;
    }

    /**
     * 상태 변경 한 건을 기록합니다.
     *
     * @param fromStatus 이전 상태. 주문 생성이면 null입니다.
     * @param reason     실패 사유 등. 없으면 null입니다.
     */
    public static ExchangeOrderStatusHistory of(UUID orderId, ExchangeOrderStatus fromStatus,
                                                ExchangeOrderStatus toStatus, String reason,
                                                ChangedByType changedByType) {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(toStatus, "toStatus");
        Objects.requireNonNull(changedByType, "changedByType");
        return new ExchangeOrderStatusHistory(orderId, fromStatus, toStatus, reason, changedByType);
    }
}
