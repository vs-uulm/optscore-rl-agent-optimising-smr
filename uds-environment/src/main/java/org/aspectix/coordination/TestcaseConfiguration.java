package org.aspectix.coordination;

import java.lang.reflect.Field;
import java.util.Date;

/**
 * Helper object for conveniently storing configuration data for a test case. Since its only purpose is to ease the
 * passing of lots of parameters in a convenient way, no getter/setters for fields.
 */
public class TestcaseConfiguration {

    public int id;

    public String testcaseId;
    public String testcaseDesc;
    public Date creationTime;
    public String testcaseGitCommit;
    public int runsCompleted;
    public int numIterations;
    public int initialCollectSteps;
    public int collectStepsPerIteration;
    public int replayBufferMaxLength;
    public int batchSize;
    public String learningRate;
    public int logInterval;
    public int numEvalEpisodes;
    public int evalInterval;
    public String hiddenLayers;
    public int numClients;

    public TestcaseConfiguration(String testcaseId) {
        this.testcaseId = testcaseId;
    }

    public String getSummary() {
        Field[] testcaseFields = TestcaseConfiguration.class.getFields();

        StringBuilder desc = new StringBuilder("Configuration parameters for test case " + testcaseId + ":\n");
        for(Field f : testcaseFields) {
            try {
                desc.append(f.getName()).append(": ").append(f.get(this).toString());
                desc.append("\n");
            } catch(IllegalAccessException e) {
                e.printStackTrace();
            }
        }
        return desc.toString();
    }
}
