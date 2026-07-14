package com.artverse.application;

import com.artverse.common.BusinessException;

public class AgentBudgetExceededException extends BusinessException {

    private final String budgetKind;
    private final long limit;
    private final long usage;

    public AgentBudgetExceededException(String budgetKind, long limit, long usage) {
        super(429, "Agent budget exhausted: " + budgetKind + " " + usage + "/" + limit);
        this.budgetKind = budgetKind;
        this.limit = limit;
        this.usage = usage;
    }

    public String getBudgetKind() {
        return budgetKind;
    }

    public long getLimit() {
        return limit;
    }

    public long getUsage() {
        return usage;
    }
}
