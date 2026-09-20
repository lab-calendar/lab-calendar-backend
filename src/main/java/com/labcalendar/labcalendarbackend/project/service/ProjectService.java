package com.labcalendar.labcalendarbackend.project.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
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
    private final PreparationScheduleService schedules;
    private final Clock clock;

    public ProjectService(ResearchProjectRepository projects,
            PreparationScheduleService schedules, Clock clock) {
        this.projects = projects;
        this.schedules = schedules;
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
        // 등록만으로 캘린더에 준비 기간 바가 나타나야 한다 (기획서 3.1)
        schedules.sync(project);
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
        // 마감일이나 리드타임이 바뀌면 준비 기간도 함께 옮겨간다
        schedules.sync(project);
        return toResponse(project, LocalDate.now(clock));
    }

    /**
     * Deletes a project, and the preparation schedule it owns with it.
     *
     * <p>That schedule holds a RESTRICT foreign key onto the project. Since KAN-49 every project
     * has one, so clearing it is not a special case any more — it is simply part of deleting a
     * project, and skipping it would make every deletion fail.
     *
     * <p>It is the only thing that can reference a project ({@code uq_event_project} allows one
     * generated event per project and nothing else points here), so once it is gone the row is
     * free. There is no conflict left to report.
     */
    @Transactional
    public void delete(Long id) {
        ResearchProject project = find(id);
        schedules.removeFor(project.getId());
        projects.delete(project);
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
