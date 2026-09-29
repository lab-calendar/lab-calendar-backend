package com.labcalendar.labcalendarbackend.member.service;

import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.labcalendar.labcalendarbackend.common.exception.BusinessException;
import com.labcalendar.labcalendarbackend.common.exception.ErrorCode;
import com.labcalendar.labcalendarbackend.member.dto.MemberRequest;
import com.labcalendar.labcalendarbackend.member.dto.MemberResponse;
import com.labcalendar.labcalendarbackend.member.entity.Member;
import com.labcalendar.labcalendarbackend.member.repository.MemberRepository;

/** Lab roster management (KAN-41). */
@Service
public class MemberService {

    /** Current members first, then by name, so a picker shows who is around today. */
    private static final Sort ORDER = Sort.by(
            Sort.Order.desc("active"), Sort.Order.asc("name"), Sort.Order.asc("id"));

    private final MemberRepository members;

    public MemberService(MemberRepository members) {
        this.members = members;
    }

    /**
     * The whole roster, including people who have left.
     *
     * <p>Inactive rows are returned with their flag rather than filtered out: a picker wants the
     * current members, but the management screen has to see a former member to bring them back.
     * One list serves both and the caller decides.
     */
    @Transactional(readOnly = true)
    public List<MemberResponse> list() {
        return members.findAll(ORDER).stream().map(MemberService::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public MemberResponse get(Long id) {
        return toResponse(find(id));
    }

    @Transactional
    public MemberResponse create(MemberRequest request) {
        return toResponse(members.save(Member.register(request.name().trim(), request.active())));
    }

    @Transactional
    public MemberResponse update(Long id, MemberRequest request) {
        Member member = find(id);
        member.apply(request.name().trim(), request.active());
        return toResponse(member);
    }

    /**
     * Removes someone who was never used anywhere.
     *
     * <p>Owning an event or attending one holds a RESTRICT foreign key, and that is deliberate:
     * deleting the row would rewrite history, and a schedule would lose the person who ran it.
     * Someone who has left the lab is marked inactive instead, which is what the flag is for.
     *
     * <p>The references are counted first. Letting the foreign key raise instead would be too
     * late — the failed flush marks the transaction rollback-only, so the commit that follows
     * throws and the caller would get a server error rather than this conflict.
     */
    @Transactional
    public void delete(Long id) {
        Member member = find(id);
        if (members.countReferences(member.getId()) > 0) {
            throw new BusinessException(ErrorCode.CONFLICT);
        }
        members.delete(member);
    }

    private Member find(Long id) {
        return members.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private static MemberResponse toResponse(Member member) {
        return new MemberResponse(
                String.valueOf(member.getId()), member.getName(), member.getActive());
    }
}
