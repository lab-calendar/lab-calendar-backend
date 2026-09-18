package com.labcalendar.labcalendarbackend.expense.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.labcalendar.labcalendarbackend.expense.entity.CardExpense;

public interface CardExpenseRepository extends JpaRepository<CardExpense, Long> {
}
