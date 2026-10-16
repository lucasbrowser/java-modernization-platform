package com.github.lucasoliveira.platform.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(name = "user_customer_link_audit")
public class UserCustomerLinkAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "actor_user_id", nullable = false)
    private UUID actorUserId;

    @Column(name = "target_user_id", nullable = false)
    private UUID targetUserId;

    @Column(name = "previous_customer_id")
    private UUID previousCustomerId;

    @Column(name = "new_customer_id")
    private UUID newCustomerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private UserCustomerLinkAction action;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String justification;

    protected UserCustomerLinkAudit() {
    }

    public UserCustomerLinkAudit(
            UUID actorUserId,
            UUID targetUserId,
            UUID previousCustomerId,
            UUID newCustomerId,
            UserCustomerLinkAction action,
            String justification
    ) {
        this.actorUserId = actorUserId;
        this.targetUserId = targetUserId;
        this.previousCustomerId = previousCustomerId;
        this.newCustomerId = newCustomerId;
        this.action = action;
        this.justification = justification;
    }

    @PrePersist
    void setOccurredAtIfMissing() {
        if (occurredAt == null) {
            occurredAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getActorUserId() {
        return actorUserId;
    }

    public UUID getTargetUserId() {
        return targetUserId;
    }

    public UUID getPreviousCustomerId() {
        return previousCustomerId;
    }

    public UUID getNewCustomerId() {
        return newCustomerId;
    }

    public UserCustomerLinkAction getAction() {
        return action;
    }

    public OffsetDateTime getOccurredAt() {
        return occurredAt;
    }

    public String getJustification() {
        return justification;
    }
}
