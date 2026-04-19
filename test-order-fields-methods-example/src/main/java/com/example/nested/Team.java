package com.example.nested;

public class Team {

    private final String name;
    private Employee lead;

    public Team(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setLead(Employee lead) {
        this.lead = lead;
    }

    public Employee getLead() {
        return lead;
    }
}
