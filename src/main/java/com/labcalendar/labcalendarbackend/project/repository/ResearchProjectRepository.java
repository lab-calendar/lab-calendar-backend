package com.labcalendar.labcalendarbackend.project.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.labcalendar.labcalendarbackend.project.entity.ResearchProject;

public interface ResearchProjectRepository extends JpaRepository<ResearchProject, Long> {
    // CRUD only. The preparation schedule a project owns is reached through EventRepository,
    // keyed by research_project_id (KAN-49).
}
