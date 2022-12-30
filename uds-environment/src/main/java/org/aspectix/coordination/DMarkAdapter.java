package org.aspectix.coordination;

import java.sql.*;

/**
 * Provides methods for manipulating and querying the DMark test case DB
 */
public class DMarkAdapter {

    private final Connection conn;

    public DMarkAdapter(String dbPath) {
        this.conn = new SQLiteJDBCDriverConnection().connect(dbPath);
    }

    /**
     * Queries the testcase DB for a testcase's data, including its playbook
     * @param testcaseId The Id of the testcase we want all the details and the playbook of. If this is null, the
     *                   playbook/details of all testcases will be returned
     * @return A {@link ResultSet} containing testcase configuration and playbook
     */
    private ResultSet queryTestcase(String testcaseId) throws SQLException {
        if(testcaseId == null || testcaseId.equals("")) {
            throw new SQLException("testcaseId is Parameter of query and can not be null/empty");
        }

        String testcaseQuery = "SELECT\n" +
                "  t.testcaseId,\n" +
                "  t.description testcaseDesc,\n" +
                "  t.runsCompleted,\n" +
                "  t.creationTimestamp,\n" +
                "  c.gitCommit,\n" +
                "  c.numIterations,\n" +
                "  c.initialCollectSteps,\n" +
                "  c.collectStepsPerIteration,\n" +
                "  c.replayBufferMaxLength,\n" +
                "  c.batchSize,\n" +
                "  c.learningRate,\n" +
                "  c.logInterval,\n" +
                "  c.numEvalEpisodes,\n" +
                "  c.evalInterval,\n" +
                "  c.hiddenLayers,\n" +
                "  c.numClients\n" +
                "FROM testcase t\n" +
                "  JOIN configuration c ON t.configurationId = c.configurationId\n" +
                "WHERE t.testcaseId = ?\n" +
                "ORDER BY t.creationTimestamp ASC";

        try {
            PreparedStatement pstmt = conn.prepareStatement(testcaseQuery);
            pstmt.setString(1, testcaseId);
            return pstmt.executeQuery();
        } catch(SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Creates a new test case config in the DB
     * @param conf the configuration to insert into the DB
     * @return the generated Id of the config
     */
    public int insertNewTestcaseConfiguration(TestcaseConfiguration conf) {
        String createTestcaseConfQuery = "INSERT\n" +
                "INTO configuration (gitCommit, numIterations, initialCollectSteps, collectStepsPerIteration, " +
                "replayBufferMaxLength, batchSize, learningRate, logInterval, numEvalEpisodes, evalInterval, " +
                "hiddenLayers, numClients)\n" +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try {
            PreparedStatement pstmt = conn.prepareStatement(createTestcaseConfQuery);
            pstmt.setString(1, conf.testcaseGitCommit);
            pstmt.setInt(2, conf.numIterations);
            pstmt.setInt(3, conf.initialCollectSteps);
            pstmt.setInt(4, conf.collectStepsPerIteration);
            pstmt.setInt(5, conf.replayBufferMaxLength);
            pstmt.setInt(6, conf.batchSize);
            pstmt.setString(7, conf.learningRate);
            pstmt.setInt(8, conf.logInterval);
            pstmt.setInt(9, conf.numEvalEpisodes);
            pstmt.setInt(10, conf.evalInterval);
            pstmt.setString(11, conf.hiddenLayers);
            pstmt.setInt(12, conf.numClients);
            pstmt.execute();
            return pstmt.getGeneratedKeys().getInt(1);
        } catch(SQLException e) {
            e.printStackTrace();
        }
        return 0;
    }

    public int insertNewTestcase(Testcase testcase) {
        String createTestcaseQuery = "INSERT\n" +
                "INTO testcase (testcaseId, description, creationTimestamp, configurationId) \n" +
                "VALUES (?, ?, ?, ?)";
        try {
            PreparedStatement pstmt = conn.prepareStatement(createTestcaseQuery);
            pstmt.setString(1, testcase.getId());
            pstmt.setString(2, testcase.getDescription());
            pstmt.setString(3, new Timestamp(System.currentTimeMillis()).toString());
            pstmt.setInt(4, testcase.getTestcaseConfiguration().id);
            pstmt.executeUpdate();
            return pstmt.getGeneratedKeys().getInt(1);
        } catch(SQLException e) {
            e.printStackTrace();
            return -1;
        }
    }

    public void incrementTestcaseRunNumber(String testcaseId) {
        String incrementRunQuery = "UPDATE testcase\n" +
                "SET runsCompleted = runsCompleted + 1\n" +
                "WHERE testcaseId = ?";
        try {
            PreparedStatement pstmt = conn.prepareStatement(incrementRunQuery);
            pstmt.setString(1, testcaseId);
            pstmt.executeUpdate();
        } catch(SQLException e) {
            e.printStackTrace();
        }
    }

    /**
     *
     */
    public Testcase objectifyTestcase(String testcaseId) {
        TestcaseConfiguration testcaseConfiguration = new TestcaseConfiguration(testcaseId);

        String testcaseDescription = "";
        int runsCompleted = 0;
        Date creationTime = null;
        String testcaseGitCommit = "";
        int numIterations = 0;
        int initialCollectSteps = 0;
        int collectStepsPerIteration = 0;
        int replayBufferMaxLength = 0;
        int batchSize = 0;
        String learningRate = "";
        int logInterval = 0;
        int numEvalEpisodes = 0;
        int evalInterval = 0;
        String hiddenLayers = "";
        int numClients = 0;

        try {
            ResultSet rs = queryTestcase(testcaseId);
            while(rs.next()) {
                // extract stuff from the ResultSet
                int column = 1;
                testcaseDescription = rs.getString(++column);
                runsCompleted = rs.getInt(++column);
                creationTime = rs.getDate(++column);
                testcaseGitCommit = rs.getString(++column);
                numIterations = rs.getInt(++column);
                initialCollectSteps = rs.getInt(++column);
                collectStepsPerIteration = rs.getInt(++column);
                replayBufferMaxLength = rs.getInt(++column);
                batchSize = rs.getInt(++column);
                learningRate = rs.getString(++column);
                logInterval = rs.getInt(++column);
                numEvalEpisodes = rs.getInt(++column);
                evalInterval = rs.getInt(++column);
                hiddenLayers = rs.getString(++column);
                numClients = rs.getInt(++column);

            }

            testcaseConfiguration.testcaseDesc = testcaseDescription;
            testcaseConfiguration.runsCompleted = runsCompleted;
            testcaseConfiguration.creationTime = creationTime;
            testcaseConfiguration.testcaseGitCommit = testcaseGitCommit;
            testcaseConfiguration.numIterations = numIterations;
            testcaseConfiguration.initialCollectSteps = initialCollectSteps;
            testcaseConfiguration.collectStepsPerIteration = collectStepsPerIteration;
            testcaseConfiguration.replayBufferMaxLength = replayBufferMaxLength;
            testcaseConfiguration.batchSize = batchSize;
            testcaseConfiguration.learningRate = learningRate;
            testcaseConfiguration.logInterval = logInterval;
            testcaseConfiguration.numEvalEpisodes = numEvalEpisodes;
            testcaseConfiguration.evalInterval = evalInterval;
            testcaseConfiguration.hiddenLayers = hiddenLayers;
            testcaseConfiguration.numClients = numClients;

        } catch(SQLException e) {
            e.printStackTrace();
            System.err.println("Could not objectify testcase " + testcaseId + ". Aborting ...");
            System.exit(10);
        }

        return new Testcase(testcaseConfiguration);
    }

    public void setAutoCommit(boolean autoCommit) throws SQLException {
        conn.setAutoCommit(autoCommit);
    }

    public void commitTransaction() throws SQLException {
        conn.commit();
    }

    public void rollbackTransaction() throws SQLException {
        conn.rollback();
    }

    public void closeConnection() {
        try {
            this.conn.close();
        } catch(SQLException e) {
            e.printStackTrace();
        }
    }

    private static class SQLiteJDBCDriverConnection {

        public Connection connect(String databasePath) {
            Connection conn;
            String connUrl = "jdbc:sqlite:" + databasePath;

            try {
                conn = DriverManager.getConnection(connUrl);
                return conn;
            } catch(SQLException e) {
                e.printStackTrace();
            }
            return null;
        }

    }

}
