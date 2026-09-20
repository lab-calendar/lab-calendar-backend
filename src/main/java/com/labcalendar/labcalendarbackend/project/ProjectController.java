package com.labcalendar.labcalendarbackend.project;

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
import com.labcalendar.labcalendarbackend.project.dto.ProjectRequest;
import com.labcalendar.labcalendarbackend.project.dto.ProjectResponse;
import com.labcalendar.labcalendarbackend.project.service.ProjectService;
import com.labcalendar.labcalendarbackend.project.service.PreparationScheduleService;
import com.labcalendar.labcalendarbackend.project.dto.ScheduleSyncResponse;

/** Research project CRUD (KAN-47). */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService service;
    private final PreparationScheduleService schedules;

    public ProjectController(ProjectService service, PreparationScheduleService schedules) {
        this.service = service;
        this.schedules = schedules;
    }

    /** Active first, soonest deadline first. Also feeds the D-Day widget (KAN-52). */
    @GetMapping
    public ApiResponse<List<ProjectResponse>> list() {
        return ApiResponse.of(service.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<ProjectResponse> get(@PathVariable Long id) {
        return ApiResponse.of(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProjectResponse> create(@Valid @RequestBody ProjectRequest request) {
        return ApiResponse.of(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<ProjectResponse> update(@PathVariable Long id,
            @Valid @RequestBody ProjectRequest request) {
        return ApiResponse.of(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    /**
     * Rebuilds every preparation schedule now (KAN-49).
     *
     * <p>Registering and editing a project already syncs its own, and the nightly batch catches
     * the rest, so this is for when you do not want to wait until 03:00 — after a fix applied
     * straight to the database, or just to confirm nothing has drifted.
     *
     * <p>Counts come back rather than a bare 204: "0 created, 0 moved" is the healthy answer,
     * and anything else says something was out of step.
     *
     * <p>A write method, so only the editor tier may call it (KAN-35).
     */
    @PostMapping("/schedule-sync")
    public ApiResponse<ScheduleSyncResponse> syncSchedules() {
        PreparationScheduleService.SyncResult result = schedules.syncAll();
        return ApiResponse.of(
                new ScheduleSyncResponse(result.created(), result.moved(), result.projects()));
    }
}
