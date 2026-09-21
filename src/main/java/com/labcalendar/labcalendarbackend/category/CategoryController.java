package com.labcalendar.labcalendarbackend.category;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.labcalendar.labcalendarbackend.category.dto.CategoryResponse;
import com.labcalendar.labcalendarbackend.category.service.CategoryService;
import com.labcalendar.labcalendarbackend.common.api.ApiResponse;

/**
 * Category listing (KAN-38).
 *
 * <p>Read only. The three are fixed by the seed migration and adding or renaming one is out of
 * scope — 기획서 2.2 treats them as the lab's standard, not user data.
 */
@RestController
@RequestMapping("/api/categories")
public class CategoryController {

    private final CategoryService service;

    public CategoryController(CategoryService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<CategoryResponse>> list() {
        return ApiResponse.of(service.list());
    }
}
