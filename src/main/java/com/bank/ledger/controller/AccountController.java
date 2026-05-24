package com.bank.ledger.controller;

import com.bank.ledger.domain.Account;
import com.bank.ledger.domain.User;
import com.bank.ledger.dto.AccountResponse;
import com.bank.ledger.dto.CreateAccountRequest;
import com.bank.ledger.dto.MessageResponse;
import com.bank.ledger.exception.AccountNotFoundException;
import com.bank.ledger.exception.BankingException;
import com.bank.ledger.repository.AccountRepository;
import com.bank.ledger.repository.UserRepository;
import com.bank.ledger.service.LedgerEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.security.SecureRandom;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class AccountController {

    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final LedgerEngine ledgerEngine;

    private final SecureRandom random = new SecureRandom();

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ResponseEntity<AccountResponse> createAccount(@RequestBody CreateAccountRequest request) {
        log.info("Admin creating new account for user ID: {}", request.getUserId());
        
        User user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new BankingException("User not found", HttpStatus.BAD_REQUEST));

        // Generate a random unique 12-digit account number
        String accountNumber;
        do {
            long number = 100000000000L + random.nextLong(900000000000L);
            accountNumber = "ACT" + number;
        } while (accountRepository.existsByAccountNumber(accountNumber));

        Account.AccountType type = Account.AccountType.valueOf(request.getType().toUpperCase());
        
        // Save the account with an initial balance of 0 first
        Account account = Account.builder()
                .user(user)
                .accountNumber(accountNumber)
                .type(type)
                .currency(request.getCurrency() != null ? request.getCurrency().toUpperCase() : "USD")
                .balance(java.math.BigDecimal.ZERO)
                .status(Account.AccountStatus.ACTIVE)
                .build();

        account = accountRepository.save(account);
        log.info("Successfully created account: {} for user: {}", account.getAccountNumber(), user.getUsername());

        final String finalAccountNumber = accountNumber;

        // Seeding initial balance cleanly via GAAP-compliant double-entry deposit from reserve
        if (request.getInitialBalance() != null && request.getInitialBalance().compareTo(java.math.BigDecimal.ZERO) > 0) {
            log.info("Seeding account {} with initial balance of ${} via reserve debit deposit.", finalAccountNumber, request.getInitialBalance());
            String seedIdempotencyKey = "seed-" + finalAccountNumber;
            ledgerEngine.deposit(
                    finalAccountNumber,
                    request.getInitialBalance(),
                    "Initial Account Seed",
                    seedIdempotencyKey
            );
            // Reload the account from database to fetch updated seeded balance
            Account seededAccount = accountRepository.findByAccountNumber(finalAccountNumber)
                    .orElseThrow(() -> new AccountNotFoundException("Account not found after seeding: " + finalAccountNumber));
            account = seededAccount;
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(mapToResponse(account));
    }

    @GetMapping("/my")
    @PreAuthorize("hasRole('CUSTOMER') or hasRole('ADMIN') or hasRole('OPS')")
    public ResponseEntity<List<AccountResponse>> getMyAccounts(Principal principal) {
        User user = userRepository.findByUsername(principal.getName())
                .orElseThrow(() -> new BankingException("Current authenticated user not found", HttpStatus.UNAUTHORIZED));
        
        List<Account> accounts = accountRepository.findByUser(user);
        List<AccountResponse> response = accounts.stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(response);
    }

    @GetMapping("/users")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPS')")
    public ResponseEntity<List<java.util.Map<String, Object>>> getAllUsers() {
        log.info("Operational request: Fetching all registered users list");
        List<User> users = userRepository.findAll();
        List<java.util.Map<String, Object>> response = users.stream().map(u -> {
            java.util.Map<String, Object> map = new java.util.HashMap<>();
            map.put("id", u.getId());
            map.put("username", u.getUsername());
            map.put("fullName", u.getFirstName() + " " + u.getLastName());
            map.put("email", u.getEmail());
            map.put("roles", u.getRoles().stream().map(r -> r.getName().name()).collect(Collectors.toList()));
            return map;
        }).collect(Collectors.toList());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{accountNumber}")
    public ResponseEntity<AccountResponse> getAccountByNumber(@PathVariable String accountNumber, Principal principal) {
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountNumber));

        // Authorization logic: User must be ADMIN or OPS, or own the requested account
        boolean isStaff = SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN") || a.getAuthority().equals("ROLE_OPS"));

        boolean isOwner = account.getUser() != null && account.getUser().getUsername().equals(principal.getName());

        if (!isStaff && !isOwner) {
            log.warn("Unauthorized access attempt on account {} by user {}", accountNumber, principal.getName());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        return ResponseEntity.ok(mapToResponse(account));
    }

    @PostMapping("/{accountNumber}/freeze")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPS')")
    @Transactional
    public ResponseEntity<MessageResponse> freezeAccount(@PathVariable String accountNumber) {
        log.info("Freezing account: {}", accountNumber);
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountNumber));

        account.setStatus(Account.AccountStatus.FROZEN);
        accountRepository.save(account);

        return ResponseEntity.ok(new MessageResponse("Account " + accountNumber + " has been FROZEN successfully."));
    }

    @PostMapping("/{accountNumber}/unfreeze")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPS')")
    @Transactional
    public ResponseEntity<MessageResponse> unfreezeAccount(@PathVariable String accountNumber) {
        log.info("Unfreezing account: {}", accountNumber);
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountNumber));

        account.setStatus(Account.AccountStatus.ACTIVE);
        accountRepository.save(account);

        return ResponseEntity.ok(new MessageResponse("Account " + accountNumber + " has been activated successfully."));
    }

    private AccountResponse mapToResponse(Account account) {
        return AccountResponse.builder()
                .id(account.getId())
                .accountNumber(account.getAccountNumber())
                .type(account.getType().name())
                .currency(account.getCurrency())
                .balance(account.getBalance())
                .status(account.getStatus().name())
                .ownerUsername(account.getUser() != null ? account.getUser().getUsername() : "SYSTEM")
                .build();
    }
}
