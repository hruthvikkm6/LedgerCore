package com.bank.ledger.dto;

import lombok.*;
import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AccountResponse {
    private Long id;
    private String accountNumber;
    private String type;
    private String currency;
    private BigDecimal balance;
    private String status;
    private String ownerUsername;
}
