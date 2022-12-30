package org.aspectix.coordination;

import org.aspectix.selfoptim.stepped.UdsEnvironment;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * @author Gerhard Habiger
 */
public class TestcaseCoordinator {

    private Testcase testcase;
    private TestcaseConfiguration testcaseConf;
    private DMarkAdapter db;

    private ExecutorService threadPool;
    /**
     * Logging
     */
    private static final Logger logger = Logger.getLogger(TestcaseCoordinator.class.getName());

    public TestcaseCoordinator(String dbFilePath) throws IOException {
        this(dbFilePath, "test");
    }

    public TestcaseCoordinator(String dbPath, String testcaseId) throws
            IOException {
        this.db = new DMarkAdapter(dbPath);
        this.testcase = db.objectifyTestcase(testcaseId);
        this.testcaseConf = testcase.getTestcaseConfiguration();

        this.threadPool = Executors.newFixedThreadPool(2);

        logger.finer("TestcaseCoordinator running ...");
    }

    /**
     * Runs an entire testcase.
     * 0. Read details about testcase from DB (happened in TestcaseCoordinate Constructor)
     * 1. Start UdsEnvironment
     * 2. Start TF agent with parameters given for testcase
     * 3. Gather and save results
     */
    public void runTestcase() {
        logger.info("##### Starting testcase. Summary:");
        logger.info(testcase.getTestcaseSummary());

        // start collection UdsEnvironment
        threadPool.execute(() -> {
            logger.fine("##### Starting Collection UdsEnvironment in its own thread ...");
            UdsEnvironment udsEnvironment = new UdsEnvironment(testcaseConf.numClients, 4242);
            udsEnvironment.socketLoop();
            logger.fine("##### UdsEnvironment thread shutting down");
        });

        // start evaluation UdsEnvironment
        threadPool.execute(() -> {
            logger.fine("##### Starting Evaluation UdsEnvironment in its own thread ...");
            UdsEnvironment udsEnvironment = new UdsEnvironment(testcaseConf.numClients, 4343);
            udsEnvironment.socketLoop();
            logger.fine("##### UdsEnvironment thread shutting down");
        });

        // start TF agent with parameters for testcase
        ProcessBuilder rsyncBuilder;
        rsyncBuilder = new ProcessBuilder();
        rsyncBuilder.inheritIO();
        int pyEnvStatus = -1;
        try {
            logger.fine("##### Starting Python environment for training agent ...");
            rsyncBuilder.command("/usr/local/bin/fish", "-c", pythonCommand());
            logger.fine("Python command: " + String.join(" ", rsyncBuilder.command()));
            pyEnvStatus = rsyncBuilder.start().waitFor();
            logger.fine("##### Python environment shutting down");
        } catch(IOException | InterruptedException e) {
            e.printStackTrace();
        }

        // mark the test case run as completed in the database
        if(pyEnvStatus == 0) {
            db.incrementTestcaseRunNumber(testcaseConf.testcaseId);
        }

        // wait 2 secs, just for safety and termination of everything. Probably unnecessary
        try {
            Thread.sleep(2 * 1000);
        } catch(InterruptedException e) {
            e.printStackTrace();
        }

        logger.info("##### Finished run #" + testcaseConf.runsCompleted + " of testcase " + testcase.getId());
    }

    private String pythonCommand() {
        return "cd ../rl-uds-optimizer; source ./venv/bin/activate.fish; python3 dqn-train-eval-uds.py " +
                testcaseConf.testcaseId + " " + testcaseConf.runsCompleted + " '" +
                testcaseConf.numIterations + ":" +
                testcaseConf.initialCollectSteps + ":" +
                testcaseConf.collectStepsPerIteration + ":" +
                testcaseConf.replayBufferMaxLength + ":" +
                testcaseConf.batchSize + ":" +
                testcaseConf.learningRate + ":" +
                testcaseConf.logInterval + ":" +
                testcaseConf.numEvalEpisodes + ":" +
                testcaseConf.evalInterval + ":" +
                testcaseConf.hiddenLayers + "'";
    }

    private static void threadSleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch(InterruptedException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        if(args.length < 1) {
            System.out.println("Please specify a test case DB file path.");
            System.exit(1);
        }
        String dbFilePath = args[0];
        if(args.length >= 2) {
            List<String> testcases = new LinkedList<>(Arrays.asList(args).subList(1, args.length));

                for(String testcaseId : testcases) {
                    try {
                        TestcaseCoordinator coordinator = new TestcaseCoordinator(dbFilePath, testcaseId);
                        coordinator.runTestcase();
                        // wait 2 seconds inbetween test cases
                        threadSleep(2000);
                    } catch(IOException e) {
                        e.printStackTrace();
                        System.err.println("ERROR: Could not instantiate TestCoordinator for testcase " + testcaseId);
                    }
                }

                // just kill this abomination, as it often won't die silently
                System.exit(0);
            } else {
                System.out.println("Please specify a test case DB file path and test case ID(s) for " +
                        "the TestcaseCoordinator");
                System.exit(2);
            }
    }
}
