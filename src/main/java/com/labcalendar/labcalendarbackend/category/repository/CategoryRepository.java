package com.labcalendar.labcalendarbackend.category.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import com.labcalendar.labcalendarbackend.category.entity.Category;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    /** {@code code} is the contract the frontend keys its colours and URL filter on. */
    Optional<Category> findByCode(String code);
}
