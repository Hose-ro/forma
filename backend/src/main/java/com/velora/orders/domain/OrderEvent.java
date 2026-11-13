package com.velora.orders.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "order_events")
public class OrderEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id")
    private Order order;
    @Column(nullable = false)
    private String type;
    @Enumerated(EnumType.STRING)
    @Column(name = "from_status")
    private OrderStatus fromStatus;
    @Enumerated(EnumType.STRING)
    @Column(name = "to_status")
    private OrderStatus toStatus;
    private String note;
    @Column(name = "actor_user_id")
    private UUID actorUserId;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected OrderEvent() {
    }

    OrderEvent(Order order, String type, OrderStatus from, OrderStatus to, String note, UUID actor, Instant at) {
        this.order = order;
        this.type = type;
        this.fromStatus = from;
        this.toStatus = to;
        this.note = note;
        this.actorUserId = actor;
        this.createdAt = at;
    }

    public String getType() { return type; }
    public OrderStatus getFromStatus() { return fromStatus; }
    public OrderStatus getToStatus() { return toStatus; }
    public String getNote() { return note; }
    public UUID getActorUserId() { return actorUserId; }
    public Instant getCreatedAt() { return createdAt; }
}
