package com.labcalendar.labcalendarbackend.project.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.labcalendar.labcalendarbackend.project.entity.ResearchProject;

public interface ResearchProjectRepository extends JpaRepository<ResearchProject, Long> {

    /**
     * How many generated schedules still point at this project.
     *
     * <p>Asked before deleting rather than letting the foreign key raise. Catching the violation
     * would be too late: the failed flush marks the transaction rollback-only, so the commit that
     * follows throws and the caller gets a server error instead of the conflict we meant to send.
     */
    @Query("SELECT COUNT(e) FROM Event e WHERE e.researchProjectId = :projectId")
    long countGeneratedEvents(@Param("projectId") Long projectId);
}
