package com.mmagym.bot.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Admins who can access the Admin Panel via WhatsApp.
 * Primary admin (gym owner) should be seeded on first run.
 * Additional admins can be added via the /addadmin command.
 */
@Entity
@Table(name = "admins")
public class Admin {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 20)
    private String phone;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public Admin() {}

    public Admin(String phone, String name) {
        this.phone = phone;
        this.name  = name;
    }

    public Long getId()                       { return id; }
    public String getPhone()                  { return phone; }
    public void setPhone(String phone)        { this.phone = phone; }
    public String getName()                   { return name; }
    public void setName(String name)          { this.name = name; }
    public boolean isActive()                 { return active; }
    public void setActive(boolean active)     { this.active = active; }
    public LocalDateTime getCreatedAt()       { return createdAt; }
}
