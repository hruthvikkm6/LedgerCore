package com.bank.ledger.dto;

import lombok.*;
import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateAccountRequest {
    private Long userId;
    private String type; // ASSET, LIABILITY
    private String currency; // Default USD
    private BigDecimal initialBalance; // For starting reserves or test deposits
}
