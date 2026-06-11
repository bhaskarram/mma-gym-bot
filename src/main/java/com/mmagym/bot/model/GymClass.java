package com.mmagym.bot.model;

import jakarta.persistence.*;

import java.time.LocalTime;

@Entity
@Table(name = "gym_classes")
public class GymClass {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String className;

    @Column(nullable = false)
    private byte dayOfWeek; // 1=Monday … 7=Sunday

    @Column(nullable = false)
    private LocalTime classTime;

    @Column(nullable = false)
    private int maxCapacity = 20;

    @Column(length = 100)
    private String coach;

    @Column(nullable = false)
    private boolean isActive = true;

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }

    public byte getDayOfWeek() { return dayOfWeek; }
    public void setDayOfWeek(byte dayOfWeek) { this.dayOfWeek = dayOfWeek; }

    public LocalTime getClassTime() { return classTime; }
    public void setClassTime(LocalTime classTime) { this.classTime = classTime; }

    public int getMaxCapacity() { return maxCapacity; }
    public void setMaxCapacity(int maxCapacity) { this.maxCapacity = maxCapacity; }

    public String getCoach() { return coach; }
    public void setCoach(String coach) { this.coach = coach; }

    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { isActive = active; }
}
