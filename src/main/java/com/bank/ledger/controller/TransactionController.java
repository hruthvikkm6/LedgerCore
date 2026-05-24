package com.bank.ledger.controller;

import com.bank.ledger.domain.Account;
import com.bank.ledger.domain.LedgerEntry;
import com.bank.ledger.domain.Transaction;
import com.bank.ledger.dto.*;
import com.bank.ledger.exception.AccountNotFoundException;
import com.bank.ledger.exception.BankingException;
import com.bank.ledger.repository.AccountRepository;
import com.bank.ledger.repository.LedgerEntryRepository;
import com.bank.ledger.service.LedgerEngine;
import com.bank.ledger.service.StatementExportService;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class TransactionController {

    private final LedgerEngine ledgerEngine;
    private final StatementExportService statementExportService;
    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    @PostMapping("/deposit")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<TransactionResponse> deposit(
            @RequestBody DepositRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            Principal principal) {
        
        log.info("Processing Deposit of ${} into account: {} (Idempotency: {})", 
            request.getAmount(), request.getAccountNumber(), idempotencyKey);
            
        verifyAccountOwnership(request.getAccountNumber(), principal.getName(), true);

        Transaction tx = ledgerEngine.deposit(
                request.getAccountNumber(),
                request.getAmount(),
                request.getDescription(),
                idempotencyKey
        );

        return ResponseEntity.ok(mapToResponse(tx));
    }

    @PostMapping("/withdraw")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<TransactionResponse> withdraw(
            @RequestBody WithdrawRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            Principal principal) {
        
        log.info("Processing Withdrawal of ${} from account: {} (Idempotency: {})", 
            request.getAmount(), request.getAccountNumber(), idempotencyKey);
            
        verifyAccountOwnership(request.getAccountNumber(), principal.getName(), true);

        Transaction tx = ledgerEngine.withdraw(
                request.getAccountNumber(),
                request.getAmount(),
                request.getDescription(),
                idempotencyKey
        );

        return ResponseEntity.ok(mapToResponse(tx));
    }

    @PostMapping("/transfer")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<TransactionResponse> transfer(
            @RequestBody TransferRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            Principal principal) {
        
        log.info("Processing Transfer of ${} from {} to {} (Idempotency: {})", 
            request.getAmount(), request.getFromAccountNumber(), request.getToAccountNumber(), idempotencyKey);
            
        verifyAccountOwnership(request.getFromAccountNumber(), principal.getName(), true);

        Transaction tx = ledgerEngine.transfer(
                request.getFromAccountNumber(),
                request.getToAccountNumber(),
                request.getAmount(),
                request.getDescription(),
                idempotencyKey
        );

        return ResponseEntity.ok(mapToResponse(tx));
    }

    @GetMapping("/{accountNumber}/history")
    public ResponseEntity<List<LedgerEntryResponse>> getTransactionHistory(
            @PathVariable String accountNumber,
            Principal principal) {
        
        log.info("Fetching transaction history for account: {}", accountNumber);
        verifyAccountOwnership(accountNumber, principal.getName(), false);

        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new AccountNotFoundException("Account " + accountNumber + " not found."));

        List<LedgerEntry> entries = ledgerEntryRepository.findByAccountIdOrderByCreatedAtDesc(account.getId());
        List<LedgerEntryResponse> response = entries.stream()
                .map(this::mapToEntryResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(response);
    }

    @GetMapping(value = "/{accountNumber}/statement", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> getAccountStatement(
            @PathVariable String accountNumber,
            Principal principal) {
        
        log.info("Exporting account statement PDF for: {}", accountNumber);
        verifyAccountOwnership(accountNumber, principal.getName(), false);

        byte[] pdfBytes = statementExportService.generateStatementPdf(accountNumber);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDispositionFormData("attachment", "statement_" + accountNumber + ".pdf");
        headers.setCacheControl("must-revalidate, post-check=0, pre-check=0");

        return new ResponseEntity<>(pdfBytes, headers, HttpStatus.OK);
    }

    private void verifyAccountOwnership(String accountNumber, String currentUsername, boolean isWriteOperation) {
        // Internal reserve is locked for direct user interaction
        if ("BANK_CASH_RESERVE".equals(accountNumber)) {
            boolean isStaff = SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN") || a.getAuthority().equals("ROLE_OPS"));
            if (!isStaff) {
                throw new BankingException("Access Denied: Direct interactions with BANK_CASH_RESERVE are restricted to staff roles.", HttpStatus.FORBIDDEN);
            }
            return;
        }

        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new AccountNotFoundException("Account " + accountNumber + " not found."));

        boolean isStaff = SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN") || a.getAuthority().equals("ROLE_OPS"));

        boolean isOwner = account.getUser() != null && account.getUser().getUsername().equals(currentUsername);

        if (isWriteOperation) {
            // For write operations (deposit, withdraw, transfer), ONLY the owner is authorized.
            // Staff roles have absolutely zero right to spend/touch customer funds!
            if (!isOwner) {
                log.warn("Access Denied: User {} (isStaff={}) attempted write operation on account {} without ownership.", currentUsername, isStaff, accountNumber);
                throw new BankingException("Access Denied: Only the account owner can execute writes on account " + accountNumber, HttpStatus.FORBIDDEN);
            }
        } else {
            // For read operations (history, statement, balance check), either the owner or staff can access it.
            if (!isStaff && !isOwner) {
                log.warn("Access Denied: User {} attempted read operation on account {} without ownership or staff clearance.", currentUsername, accountNumber);
                throw new BankingException("Access Denied: You do not have permission to view account " + accountNumber, HttpStatus.FORBIDDEN);
            }
        }
    }

    private TransactionResponse mapToResponse(Transaction tx) {
        return TransactionResponse.builder()
                .id(tx.getId())
                .type(tx.getType().name())
                .status(tx.getStatus().name())
                .description(tx.getDescription())
                .createdAt(tx.getCreatedAt())
                .build();
    }

    private LedgerEntryResponse mapToEntryResponse(LedgerEntry entry) {
        return LedgerEntryResponse.builder()
                .id(entry.getId())
                .transactionId(entry.getTransaction().getId())
                .entryType(entry.getEntryType().name())
                .amount(entry.getAmount())
                .balanceAfter(entry.getBalanceAfter())
                .createdAt(entry.getCreatedAt())
                .description(entry.getTransaction().getDescription())
                .build();
    }

    // Embed LedgerEntryResponse inside the controller or create a DTO. Let's create a nested or standard DTO class.
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class LedgerEntryResponse {
        private Long id;
        private Long transactionId;
        private String entryType;
        private java.math.BigDecimal amount;
        private java.math.BigDecimal balanceAfter;
        private java.time.OffsetDateTime createdAt;
        private String description;
    }
}
