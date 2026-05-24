package com.bank.ledger.exception;

import org.springframework.http.HttpStatus;

public class AccountNotFoundException extends BankingException {
    public AccountNotFoundException(String message) {
        super(message, HttpStatus.NOT_FOUND);
    }
}
