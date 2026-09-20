package com.labcalendar.labcalendarbackend.project.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.labcalendar.labcalendarbackend.project.entity.ResearchProject;

public interface ResearchProjectRepository extends JpaRepository<ResearchProject, Long> {
}
