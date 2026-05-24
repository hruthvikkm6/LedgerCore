package com.bank.ledger.dto;

import lombok.*;
import java.time.OffsetDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TransactionResponse {
    private Long id;
    private String type;
    private String status;
    private String description;
    private OffsetDateTime createdAt;
}
