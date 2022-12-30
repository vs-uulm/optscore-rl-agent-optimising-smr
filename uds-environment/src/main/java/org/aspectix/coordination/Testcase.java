package org.aspectix.coordination;

import javax.xml.bind.DatatypeConverter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * A testcase consists of
 * - a id which is a hash of all its following parts
 * - a {@link TestcaseConfiguration}, which specifies static parameters that stay the same during this testrun
 */
public class Testcase {

    private String id;
    private final TestcaseConfiguration testcaseConfiguration;
    private final String description;

    public Testcase(TestcaseConfiguration conf) {
        this.testcaseConfiguration = conf;
        this.description = conf.testcaseDesc;
        this.id = hashTestcase();
    }

    public String getId() {
        if(id == null) {
            updateId();
        }
        return id;
    }

    /**
     * Recalculate the id of this test case (which is a hash of its parts, see hashTestcase method)
     */
    private void updateId() {
        this.id = hashTestcase();
    }

    public TestcaseConfiguration getTestcaseConfiguration() {
        return testcaseConfiguration;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Creates a summary of all this testcases configuration parameters and playbook actions for documentation purposes
     * @return A string containing all details of this testcase, which can for example direcly be saved to a file
     */
    public String getTestcaseSummary() {
        return "Summary for test case " + id + ":\n" +
                testcaseConfiguration.getSummary();
    }

    /**
     * Returns a hash for this testcase, by hashing all of its configuration and workloadPlaybook entries
     * This hash in a Hex representation is used as the testcaseId and will be automatically set when this method runs
     * @return A hash of the testcase, which is also its Id used to reference it in the DB
     */
    public String hashTestcase() {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");

            /*
                included in a testcase hash:
                - String testcaseGitCommit;
                - int numIterations = 0;
                - int initialCollectSteps = 0;
                - int collectStepsPerIteration = 0;
                - int replayBufferMaxLength = 0;
                - int batchSize = 0;
                - String learningRate = "";
                - int logInterval = 0;
                - int numEvalEpisodes = 0;
                - int evalInterval = 0;
                - String hiddenLayers = "";
                - int numClients = 0;
             */

            // first the test case configuration
            TestcaseConfiguration conf = this.getTestcaseConfiguration();
            sha.update(conf.testcaseGitCommit.getBytes(StandardCharsets.UTF_8));
            // ByteBuffer for the 9 ints numOfActiveCores, udsConfPrim, udsConfSteps
            ByteBuffer confBuffer = ByteBuffer.allocate(9 * 4);
            confBuffer.putInt(conf.numIterations);
            confBuffer.putInt(conf.initialCollectSteps);
            confBuffer.putInt(conf.collectStepsPerIteration);
            confBuffer.putInt(conf.replayBufferMaxLength);
            confBuffer.putInt(conf.batchSize);
            confBuffer.putInt(conf.logInterval);
            confBuffer.putInt(conf.numEvalEpisodes);
            confBuffer.putInt(conf.evalInterval);
            confBuffer.putInt(conf.numClients);
            confBuffer.flip();
            sha.update(confBuffer);
            // Strings
            sha.update(conf.learningRate.getBytes(StandardCharsets.UTF_8));
            sha.update(conf.hiddenLayers.getBytes(StandardCharsets.UTF_8));

            byte[] hash = sha.digest();
            this.id = DatatypeConverter.printHexBinary(hash);
            return this.id;
        } catch(NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        return null;
    }
}
