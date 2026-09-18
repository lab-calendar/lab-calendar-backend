package com.labcalendar.labcalendarbackend.project.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.labcalendar.labcalendarbackend.common.exception.BusinessException;
import com.labcalendar.labcalendarbackend.common.exception.ErrorCode;
import com.labcalendar.labcalendarbackend.project.dto.ProjectRequest;
import com.labcalendar.labcalendarbackend.project.dto.ProjectResponse;
import com.labcalendar.labcalendarbackend.project.entity.ResearchProject;
import com.labcalendar.labcalendarbackend.project.repository.ResearchProjectRepository;

/** Research project CRUD (KAN-47) with the server-computed schedule values (KAN-48, KAN-50). */
@Service
public class ProjectService {

    /**
     * Active first, then soonest deadline. The reason to open this screen is "what do I have to
     * prepare next", so a finished project should not sit above a live one.
     */
    private static final Sort ORDER = Sort.by(
            Sort.Order.desc("active"), Sort.Order.asc("endDate"), Sort.Order.asc("id"));

    private final ResearchProjectRepository projects;
    private final Clock clock;

    public ProjectService(ResearchProjectRepository projects, Clock clock) {
        this.projects = projects;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ProjectResponse> list() {
        LocalDate today = LocalDate.now(clock);
        return projects.findAll(ORDER).stream().map(project -> toResponse(project, today)).toList();
    }

    @Transactional(readOnly = true)
    public ProjectResponse get(Long id) {
        return toResponse(find(id), LocalDate.now(clock));
    }

    @Transactional
    public ProjectResponse create(ProjectRequest request) {
        ResearchProject project = projects.save(ResearchProject.register(
                request.name().trim(),
                request.normalizedSubmissionStage(),
                request.endDate(),
                request.leadTimeDays(),
                request.active()));
        return toResponse(project, LocalDate.now(clock));
    }

    @Transactional
    public ProjectResponse update(Long id, ProjectRequest request) {
        ResearchProject project = find(id);
        project.apply(
                request.name().trim(),
                request.normalizedSubmissionStage(),
                request.endDate(),
                request.leadTimeDays(),
                request.active());
        return toResponse(project, LocalDate.now(clock));
    }

    /**
     * Deletes a project.
     *
     * <p>Its generated schedule holds a RESTRICT foreign key, so a project that already has one
     * cannot simply disappear. Clearing that row belongs with the batch that creates it (KAN-49);
     * until then no project has one. The constraint is surfaced as a conflict rather than a
     * server error so the failure is at least legible if the order ever gets reversed.
     */
    @Transactional
    public void delete(Long id) {
        ResearchProject project = find(id);
        try {
            projects.delete(project);
            projects.flush();
        } catch (DataIntegrityViolationException blocked) {
            throw new BusinessException(ErrorCode.CONFLICT);
        }
    }

    private ResearchProject find(Long id) {
        return projects.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private ProjectResponse toResponse(ResearchProject project, LocalDate today) {
        return new ProjectResponse(
                String.valueOf(project.getId()),
                project.getName(),
                project.getSubmissionType(),
                project.getEndDate(),
                project.getLeadTimeDays(),
                project.getActive(),
                ProjectSchedule.dDay(today, project.getEndDate()),
                ProjectSchedule.preparationStart(project.getEndDate(), project.getLeadTimeDays()));
    }
}
