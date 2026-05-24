package com.bank.ledger.exception;

import org.springframework.http.HttpStatus;

public class InsufficientFundsException extends BankingException {
    public InsufficientFundsException(String message) {
        super(message, HttpStatus.UNPROCESSABLE_ENTITY);
    }
}
