package com.bank.ledger.domain;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "accounts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Account {

    public enum AccountType {
        ASSET,       // Bank vault, reserves, cash
        LIABILITY,   // Customer deposits (bank owes to customer)
        EQUITY,      // Bank's capital
        REVENUE,     // Bank fee income
        EXPENSE      // Operating costs
    }

    public enum AccountStatus {
        ACTIVE,
        FROZEN
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "account_number", unique = true, nullable = false, length = 30)
    private String accountNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountType type;

    @Column(nullable = false, length = 3)
    @Builder.Default
    private String currency = "USD";

    @Column(nullable = false, precision = 15, scale = 4)
    @Builder.Default
    private BigDecimal balance = new BigDecimal("0.0000");

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private AccountStatus status = AccountStatus.ACTIVE;

    @Column(name = "created_at", updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at")
    @Builder.Default
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
        updatedAt = OffsetDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    /**
     * Helper to adjust the account balance according to GAAP/IAS accounting rules:
     * - Assets/Expenses: Debits increase balance (+), Credits decrease balance (-).
     * - Liabilities/Equity/Revenue: Credits increase balance (+), Debits decrease balance (-).
     */
    public void applyEntry(LedgerEntry.EntryType entryType, BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        
        boolean isIncrease;
        if (type == AccountType.ASSET || type == AccountType.EXPENSE) {
            isIncrease = (entryType == LedgerEntry.EntryType.DEBIT);
        } else { // LIABILITY, EQUITY, REVENUE
            isIncrease = (entryType == LedgerEntry.EntryType.CREDIT);
        }

        if (isIncrease) {
            this.balance = this.balance.add(amount);
        } else {
            this.balance = this.balance.subtract(amount);
        }
    }
}
