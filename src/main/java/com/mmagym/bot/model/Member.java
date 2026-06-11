package com.mmagym.bot.model;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "members")
public class Member {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, unique = true, length = 20)
    private String phone;

    private LocalDate joinDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PlanType planType;

    private LocalDate planExpiry;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus paymentStatus = PaymentStatus.UNPAID;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MemberStatus status = MemberStatus.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BeltRank beltRank = BeltRank.WHITE;

    @Column(length = 200)
    private String emergencyContact;

    @Column(columnDefinition = "TEXT")
    private String injuries;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public LocalDate getJoinDate() { return joinDate; }
    public void setJoinDate(LocalDate joinDate) { this.joinDate = joinDate; }

    public PlanType getPlanType() { return planType; }
    public void setPlanType(PlanType planType) { this.planType = planType; }

    public LocalDate getPlanExpiry() { return planExpiry; }
    public void setPlanExpiry(LocalDate planExpiry) { this.planExpiry = planExpiry; }

    public PaymentStatus getPaymentStatus() { return paymentStatus; }
    public void setPaymentStatus(PaymentStatus paymentStatus) { this.paymentStatus = paymentStatus; }

    public MemberStatus getStatus() { return status; }
    public void setStatus(MemberStatus status) { this.status = status; }

    public BeltRank getBeltRank() { return beltRank; }
    public void setBeltRank(BeltRank beltRank) { this.beltRank = beltRank; }

    public String getEmergencyContact() { return emergencyContact; }
    public void setEmergencyContact(String emergencyContact) { this.emergencyContact = emergencyContact; }

    public String getInjuries() { return injuries; }
    public void setInjuries(String injuries) { this.injuries = injuries; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public enum PlanType {
        MONTHLY, QUARTERLY, ANNUAL;

        public LocalDate calculateExpiry(LocalDate from) {
            return switch (this) {
                case MONTHLY -> from.plusMonths(1);
                case QUARTERLY -> from.plusMonths(3);
                case ANNUAL -> from.plusYears(1);
            };
        }
    }

    public enum PaymentStatus { PAID, UNPAID, EXPIRED }
    public enum MemberStatus  { ACTIVE, INACTIVE, SUSPENDED }
    public enum BeltRank      { WHITE, BLUE, PURPLE, BROWN, BLACK }
}
