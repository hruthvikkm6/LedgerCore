package com.bank.ledger.exception;

import org.springframework.http.HttpStatus;

public class AccountFrozenException extends BankingException {
    public AccountFrozenException(String message) {
        super(message, HttpStatus.FORBIDDEN);
    }
}
