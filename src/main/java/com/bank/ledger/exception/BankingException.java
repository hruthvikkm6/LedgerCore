package com.bank.ledger.exception;

import org.springframework.http.HttpStatus;

public class BankingException extends RuntimeException {
    
    private final HttpStatus status;

    public BankingException(String message, HttpStatus status) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
