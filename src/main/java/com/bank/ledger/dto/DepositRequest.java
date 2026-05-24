package com.bank.ledger.dto;

import lombok.*;
import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DepositRequest {
    private String accountNumber;
    private BigDecimal amount;
    private String description;
}
