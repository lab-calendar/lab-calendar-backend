package com.labcalendar.labcalendarbackend.event;

import java.time.LocalDate;
import java.util.List;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import com.labcalendar.labcalendarbackend.common.api.ApiResponse;
import com.labcalendar.labcalendarbackend.event.dto.EventRequest;
import com.labcalendar.labcalendarbackend.event.dto.EventResponse;
import com.labcalendar.labcalendarbackend.event.service.EventService;

/** Event CRUD (KAN-39) and range listing (KAN-40). */
@RestController
@RequestMapping("/api/events")
public class EventController {

    private final EventService service;

    public EventController(EventService service) {
        this.service = service;
    }

    /**
     * Events overlapping {@code [from, to]} — both ends inclusive (KAN-40).
     *
     * <p>{@code categories} takes category keys, the same vocabulary the rest of the API speaks,
     * rather than the numeric ids the ticket sketched. Omit it for every category; sending it
     * empty asks for none. The calendar does not send it at all — it filters the month it already
     * holds so that toggling a category does not refetch (KAN-43).
     */
    @GetMapping
    public ApiResponse<List<EventResponse>> list(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) List<String> categories) {
        return ApiResponse.of(service.list(from, to, categories));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<EventResponse> create(@Valid @RequestBody EventRequest request) {
        return ApiResponse.of(service.create(request));
    }

    @GetMapping("/{id}")
    public ApiResponse<EventResponse> get(@PathVariable Long id) {
        return ApiResponse.of(service.get(id));
    }

    @PutMapping("/{id}")
    public ApiResponse<EventResponse> update(@PathVariable Long id,
            @Valid @RequestBody EventRequest request) {
        return ApiResponse.of(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
