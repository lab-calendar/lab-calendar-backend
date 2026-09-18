package com.labcalendar.labcalendarbackend.member.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.labcalendar.labcalendarbackend.member.entity.Member;

public interface MemberRepository extends JpaRepository<Member, Long> {
}
