package com.mmagym.bot.model;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "attendance",
       uniqueConstraints = @UniqueConstraint(name = "uq_member_date",
                                             columnNames = {"member_id", "class_date"}))
public class Attendance {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Column(nullable = false)
    private LocalDate classDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AttendanceStatus status = AttendanceStatus.PRESENT;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MarkedBy markedBy = MarkedBy.SELF;

    @Column(length = 50)
    private String classType;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Member getMember() { return member; }
    public void setMember(Member member) { this.member = member; }

    public LocalDate getClassDate() { return classDate; }
    public void setClassDate(LocalDate classDate) { this.classDate = classDate; }

    public AttendanceStatus getStatus() { return status; }
    public void setStatus(AttendanceStatus status) { this.status = status; }

    public MarkedBy getMarkedBy() { return markedBy; }
    public void setMarkedBy(MarkedBy markedBy) { this.markedBy = markedBy; }

    public String getClassType() { return classType; }
    public void setClassType(String classType) { this.classType = classType; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public enum AttendanceStatus { PRESENT, ABSENT, LATE }
    public enum MarkedBy         { SELF, ADMIN, QR }
}
