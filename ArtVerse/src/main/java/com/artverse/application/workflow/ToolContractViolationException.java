package com.artverse.application.workflow;

import com.artverse.common.BusinessException;

public class ToolContractViolationException extends BusinessException {

    public ToolContractViolationException(String message) {
        super(422, message);
    }
}
