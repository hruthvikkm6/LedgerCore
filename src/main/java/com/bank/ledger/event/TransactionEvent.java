package com.bank.ledger.event;

import lombok.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class TransactionEvent {
    private Long transactionId;
    private String type;
    private String fromAccountNumber;
    private String toAccountNumber;
    private BigDecimal amount;
    private OffsetDateTime timestamp;
    private String description;
}
