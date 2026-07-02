package com.example.demo.service;

import com.example.demo.entity.Account;
import com.example.demo.entity.AccountStatus;
import com.example.demo.exception.BusinessException;
import com.example.demo.repository.AccountRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class AccountService {

    private final AccountRepository repository;

    public AccountService(AccountRepository repository) {
        this.repository = repository;
    }

    public Account createAccount(Account request) {
        if (request.getUserId() == null || request.getCurrency() == null) {
            throw new BusinessException("userId и currency являются обязательными", HttpStatus.BAD_REQUEST);
        }

        List<String> allowedCurrencies = List.of("RUB", "USD", "EUR", "GBP", "CNY");
        if (!allowedCurrencies.contains(request.getCurrency())) {
            throw new BusinessException("Недопустимая валюта", HttpStatus.BAD_REQUEST);
        }

        List<Account> userAccounts = repository.findByUserId(request.getUserId());
        long sameCurrencyCount = userAccounts.stream()
                .filter(a -> a.getCurrency().equals(request.getCurrency()))
                .count();
        if (sameCurrencyCount >= 10) {
            throw new BusinessException("У пользователя не может быть более 10 счетов в одной валюте", HttpStatus.BAD_REQUEST);
        }

        Account newAccount = new Account();
        newAccount.setUserId(request.getUserId());
        newAccount.setCurrency(request.getCurrency());
        newAccount.setName(request.getName());
        newAccount.setBalance(request.getBalance() != null ? request.getBalance() : BigDecimal.ZERO);
        newAccount.setStatus(AccountStatus.ACTIVE);
        newAccount.setAccountNumber(UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        newAccount.setCreatedAt(LocalDateTime.now());
        newAccount.setUpdatedAt(LocalDateTime.now());

        return repository.save(newAccount);
    }

    public Account getById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new BusinessException("Счёт с указанным ID не найден", HttpStatus.NOT_FOUND));
    }

    public List<Account> getByUserId(Long userId) {
        return repository.findByUserId(userId);
    }

    public Account updateAccount(Long id, Account patchRequest) {
        Account existing = getById(id);

        if (patchRequest.getId() != null || patchRequest.getUserId() != null ||
                patchRequest.getAccountNumber() != null || patchRequest.getCurrency() != null ||
                patchRequest.getCreatedAt() != null) {
            throw new BusinessException("Попытка изменить запрещенное поле", HttpStatus.BAD_REQUEST);
        }

        if (patchRequest.getBalance() != null) {
            throw new BusinessException("Прямое изменение поля balance через PATCH запрещено", HttpStatus.BAD_REQUEST);
        }

        if (existing.getStatus() == AccountStatus.CLOSED) {
            throw new BusinessException("Счёт со статусом CLOSED нельзя изменять", HttpStatus.BAD_REQUEST);
        }

        if (existing.getStatus() == AccountStatus.FROZEN) {
            if (patchRequest.getStatus() == null && patchRequest.getName() != null) {
                existing.setName(patchRequest.getName());
            } else if (patchRequest.getStatus() == AccountStatus.ACTIVE) {
                existing.setStatus(AccountStatus.ACTIVE);
                if (patchRequest.getName() != null) existing.setName(patchRequest.getName());
            } else if (patchRequest.getStatus() == AccountStatus.CLOSED) {
                validateClosure(existing);
                existing.setStatus(AccountStatus.CLOSED);
            } else {
                throw new BusinessException("Счёт со статусом FROZEN допускает изменение только поля name или перевод в ACTIVE/CLOSED", HttpStatus.BAD_REQUEST);
            }
            existing.setUpdatedAt(LocalDateTime.now());
            return repository.save(existing);
        }

        if (patchRequest.getName() != null) {
            existing.setName(patchRequest.getName());
        }

        if (patchRequest.getStatus() != null) {
            AccountStatus newStatus = patchRequest.getStatus();
            if (newStatus == AccountStatus.CLOSED) {
                validateClosure(existing);
            }
            existing.setStatus(newStatus);
        }

        existing.setUpdatedAt(LocalDateTime.now());
        return repository.save(existing);
    }

    private void validateClosure(Account account) {
        if (account.getBalance().compareTo(BigDecimal.ZERO) != 0) {
            throw new BusinessException("Нельзя перевести в статус CLOSED, если на нём есть ненулевой баланс", HttpStatus.CONFLICT);
        }
    }

    public void deleteAccount(Long id) {
        Account account = getById(id);
        if (account.getStatus() != AccountStatus.CLOSED) {
            throw new BusinessException("Попытка удалить ACTIVE или FROZEN счёт должна возвращать 409 Conflict", HttpStatus.CONFLICT);
        }
        repository.delete(account);
    }
}