package com.labcalendar.labcalendarbackend.member.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.labcalendar.labcalendarbackend.member.entity.Member;

public interface MemberRepository extends JpaRepository<Member, Long> {

    /**
     * How many rows still point at this member.
     *
     * <p>Asked before deleting rather than letting the foreign key raise. Catching the violation
     * would be too late: the failed flush marks the transaction rollback-only, so the commit that
     * follows throws and the caller gets a server error instead of the conflict we meant to send.
     */
    @Query("""
            SELECT (SELECT COUNT(e) FROM Event e WHERE e.ownerMemberId = :memberId)
                 + (SELECT COUNT(p) FROM EventParticipant p WHERE p.memberId = :memberId)
            """)
    long countReferences(@Param("memberId") Long memberId);
}
