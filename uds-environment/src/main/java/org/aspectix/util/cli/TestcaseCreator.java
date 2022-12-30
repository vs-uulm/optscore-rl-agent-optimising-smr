package org.aspectix.util.cli;

import org.aspectix.coordination.DMarkAdapter;
import org.aspectix.coordination.Testcase;
import org.aspectix.coordination.TestcaseConfiguration;
import org.beryx.textio.TextIO;
import org.beryx.textio.TextIoFactory;
import org.beryx.textio.TextTerminal;
import org.beryx.textio.jline.JLineTextTerminalProvider;

import java.sql.SQLException;

public class TestcaseCreator {

    private final TextIO textIO;
    private final TextTerminal<?> terminal;

    private final DMarkAdapter db;

    /**
     * The id of the workload that is to be used for creating a new test case. Can be newly created or pre-existing,
     * both cases will be handled by the appropriate methods.
     */
    private int workloadId;

    public TestcaseCreator(String dbPath) {
        this.textIO = TextIoFactory.getTextIO();
        this.terminal = textIO.getTextTerminal();
        //terminal.println(terminal.getClass().getName());
        this.db = new DMarkAdapter(dbPath);

        // disable autoCommit in the DB, so we can roll back the transaction when something goes awry
        try {
            db.setAutoCommit(false);
        } catch(SQLException e) {
            e.printStackTrace();
            System.err.println("Could not disable autoCommit in the DB. Aborting");
            System.exit(2);
        }
    }

    private TestcaseConfiguration determineTestcaseConfiguration() {

        TestcaseConfiguration config = new TestcaseConfiguration("not yet determined");

        config.testcaseGitCommit = textIO.newStringInputReader()
                .withPattern("\\b[0-9a-f]{7,40}\\b")
                .withMinLength(7)
                .withMaxLength(40)
                .read("Provide the current git commit hash (min. length of 7 characters) of the repo under test");
        config.numIterations = textIO.newIntInputReader()
                .withMinVal(1)
                .withMaxVal(99999)
                .read("Specify how many training episodes the agent is trained");
        config.initialCollectSteps = textIO.newIntInputReader()
                .withMinVal(1)
                .withMaxVal(99999)
                .read("Specify how many initial steps are run in the environment before training begins");
        config.collectStepsPerIteration = textIO.newIntInputReader()
                .withMinVal(1)
                .withMaxVal(99999)
                .read("Specify how many steps each training episode should have");
        config.replayBufferMaxLength = textIO.newIntInputReader()
                .withMinVal(1)
                .withMaxVal(10000000)
                .read("Specify how many slots the replay buffer should have");
        config.batchSize = textIO.newIntInputReader()
                .withMinVal(1)
                .withMaxVal(50000)
                .read("Specify how big the samples that are being fetched from the replay buffer for training are");
        config.learningRate = textIO.newStringInputReader()
                .withPattern("\\d+e-?\\d+")
                .read("Specify the learning rate (in python scientific number notation, e.g.: '1e-3'");
        config.logInterval = textIO.newIntInputReader()
                .withMinVal(1)
                .withMaxVal(50000)
                .read("Specify how often stats about the training progress should be logged (every N episodes)");
        config.numEvalEpisodes = textIO.newIntInputReader()
                .withMinVal(1)
                .withMaxVal(50000)
                .read("Specify how many episodes should be used to evaluate the agent's performance during training");
        config.evalInterval = textIO.newIntInputReader()
                .withMinVal(1)
                .withMaxVal(50000)
                .read("Specify how often the current agent should be evaluated (every N episodes)");
        config.hiddenLayers = textIO.newStringInputReader()
                .withPattern("\\((\\d+,){1,}\\)")
                .read("Specify the hidden layers of the neural net of the agent, e.g.: '(10,)'");
        config.numClients = textIO.newIntInputReader()
                .withMinVal(1)
                .withMaxVal(1000)
                .read("Specify how many clients the UdsEnvironment should spawn, statically");

        // insert the new test case configuration into the DB
        config.id = db.insertNewTestcaseConfiguration(config);

        terminal.println("\n=== Created a new test case configuration (id " + config.id + "), almost done now ...\n");

        return config;
    }

    private int createTestcase(TestcaseConfiguration testcaseConfig) {
        testcaseConfig.testcaseDesc = textIO.newStringInputReader()
                .read("\nPlease enter a detailed description of the test case");

        Testcase testcase = new Testcase(testcaseConfig);

        terminal.println("=== All data required for the new testcase is now available. Inserting into DB ...");
        terminal.println("=== New testcase will have the testcaseID " + testcase.getId());

        // insert the finished test case into the DB
        int testcaseId = db.insertNewTestcase(testcase);

        if(testcaseId == -1) {
            terminal.println("ERROR: Could not save the testcase. Rolling back ...");
            try {
                db.rollbackTransaction();
                db.setAutoCommit(true);
            } catch(SQLException e) {
                e.printStackTrace();
                System.err.println("ERROR: Could not rollback the transaction. This probably means the DB is " +
                        "inconsistent now. Please manually check the DB. Aborting.");
                System.exit(4);
            }
            System.exit(6);
        }

        return testcaseId;
    }

    private void saveTestcaseToDB() {
        try {
            db.commitTransaction();
        } catch(SQLException e) {
            e.printStackTrace();
            System.err.println("ERROR: Could not commit changes to the DB! This probably means nothing was saved. " +
                    "Please check the DB manually! Aborting...");
            System.exit(3);
        }
        terminal.println("\nSuccessfully saved the testcase to the database. Congratulations.");
    }

    public void end() {
        // restoring autoCommit in the DB
        try {
            db.setAutoCommit(true);
        } catch(SQLException e) {
            e.printStackTrace();
            System.err.println("ERROR: Could not restore autoCommit in the DB! Please manually check for consistency!");
        }

        textIO.newStringInputReader().withMinLength(0).read("\nPress enter to terminate...");
        textIO.dispose();
    }


    public static void main(String[] args) {
        if(args.length < 1) {
            System.out.println("Please specify a test case DB file path.");
            System.exit(1);
        }
        String dbFilePath = args[0];
        TestcaseCreator creator = new TestcaseCreator(dbFilePath);

        // create a new configuration
        TestcaseConfiguration testcaseConfig = creator.determineTestcaseConfiguration();

        // create a new test case using the new configuration and either a new playbook or an existing one
        creator.createTestcase(testcaseConfig);

        // it seems everything went smooth. Commit transaction
        creator.saveTestcaseToDB();

        // all done, dispose of the terminal
        creator.end();
    }

}
