package org.aspectix.coordination;

public class InvalidWorkloadPlaybookException extends Exception {

    private String testcaseId;

    public InvalidWorkloadPlaybookException(String testcaseId) {
        this.testcaseId = testcaseId;
    }

}
