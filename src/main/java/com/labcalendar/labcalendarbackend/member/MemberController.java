package com.labcalendar.labcalendarbackend.member;

import java.util.List;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import com.labcalendar.labcalendarbackend.common.api.ApiResponse;
import com.labcalendar.labcalendarbackend.member.dto.MemberRequest;
import com.labcalendar.labcalendarbackend.member.dto.MemberResponse;
import com.labcalendar.labcalendarbackend.member.service.MemberService;

/** Lab roster management (KAN-41). */
@RestController
@RequestMapping("/api/members")
public class MemberController {

    private final MemberService service;

    public MemberController(MemberService service) {
        this.service = service;
    }

    /** Current members first, then by name. Feeds the attendee and owner pickers. */
    @GetMapping
    public ApiResponse<List<MemberResponse>> list() {
        return ApiResponse.of(service.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<MemberResponse> get(@PathVariable Long id) {
        return ApiResponse.of(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MemberResponse> create(@Valid @RequestBody MemberRequest request) {
        return ApiResponse.of(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<MemberResponse> update(@PathVariable Long id,
            @Valid @RequestBody MemberRequest request) {
        return ApiResponse.of(service.update(id, request));
    }

    /** Only for someone never referenced; otherwise mark them inactive (409 explains this). */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
