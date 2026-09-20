package com.labcalendar.labcalendarbackend.category.service;

import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.labcalendar.labcalendarbackend.auth.CurrentSession;
import com.labcalendar.labcalendarbackend.category.dto.CategoryResponse;
import com.labcalendar.labcalendarbackend.category.entity.Category;
import com.labcalendar.labcalendarbackend.category.repository.CategoryRepository;

/** Category listing (KAN-38). */
@Service
public class CategoryService {

    /** Withheld from the viewer tier (KAN-21). */
    private static final String CARD_CATEGORY_CODE = "card";

    /** The order the lab reads them in, fixed by the seed migration. */
    private static final Sort ORDER = Sort.by(Sort.Order.asc("sortOrder"), Sort.Order.asc("id"));

    private final CategoryRepository categories;
    private final CurrentSession session;

    public CategoryService(CategoryRepository categories, CurrentSession session) {
        this.categories = categories;
        this.session = session;
    }

    /**
     * Every category the caller is allowed to know about.
     *
     * <p>The viewer tier does not get {@code card}. Withholding the spending itself but leaving the
     * category in the filter would still answer the question that tier is not meant to be able to
     * ask — an empty "카드/경비 사용" checkbox says the data exists and is being kept from you
     * (KAN-35). Dropping it here also means the frontend does not have to know about tiers to draw
     * the filter; it renders whatever this returns.
     */
    @Transactional(readOnly = true)
    public List<CategoryResponse> list() {
        boolean includeCard = session.seesCardData();
        List<CategoryResponse> visible = new ArrayList<>();

        for (Category category : categories.findAll(ORDER)) {
            if (!includeCard && CARD_CATEGORY_CODE.equals(category.getCode())) {
                continue;
            }
            visible.add(new CategoryResponse(
                    String.valueOf(category.getId()), category.getCode(), category.getName()));
        }
        return visible;
    }
}
