# OptSCORE -- Deep Reinforcement Learning-based Optimisation for SMR

This repository contains the source code used for our research into creating RL-agents that can self-optimise [UDS](https://doi.org/10.1109/SRDS.2016.030)-based SMR systems.
The results of this research have not yet been published, but are described in detail in [Gerhard Habiger](https://github.com/ghabiger)'s inaugural dissertation on "Optimizing Deterministically Scheduled Fault-Tolerant Distributed Systems" (Ulm University, 2023).


### Project Idea, Architecture

The basic idea of this project was to use our Java-based implementation of UDS as a training environment for Deep Reinforcement Learning-based agents, implemented in Tensorflow-agents (tf-agents).

Therefore, a coupling between the two projects was implemented, using basic TCP sockets to share observations and rewards between the environment and the training neural network in each training step.

Additionally, UDS had to be modified to run in a stepped environment, i.e., an environment where execution is halted at deterministic points in time, so that observations about the environment can be sent to the agent-in-training, and actions can be received to determine the next step.

The two folders [rl-uds-optimizer](./rl-uds-optimizer) and [uds-environment](./uds-environment) contain the Tensorflow-based part and the Java-based UDS-Environment, respectively.

In order to run this project in its current work-in-progress state, please refer to the following brief description, and contact Gerhard Habiger in case there are any questions or problems.



### Setup

The provided source first has to be built and libraries provided.

For the Python-based part, i.e., the rl-uds-optimizer, it is recommended to set up a venv with the required Python 3.8+ and Tensorflow 2.8.0+ (with tf-agents as an additional requirement).

For the Java-based part, i.e., the uds-environment, one can either use Gradle to build the code or import the project into any Java-capable IDE.



### Training agents

To automatically start both environments and train agents, tooling is provided in the form of a ```org.aspectix.coordination.TestcaseCoordinator``` and a ```org.aspectix.util.cli.TestcaseCreator```. Both can be individually started using the corresponding gradle tasks _coordinator_ and _testcaseCreator_.


#### Testcases

So-called _Testcases_ specify all metadata required for a single training run. This includes parameters like the number of training episodes, learning rates, replay buffer sizes, etc. They are managed in a small SQLite database [testcase.db](./testcase.db), using the aforementioned Gradle task _testcaseCreator_, which guides through the process of creating new testcases.


#### Running Trainings

To train an agent, first create a testcase as described above. Then activate the venv, and from the uds-environment run this testcase using the Gradle task _coordinator_, providing the testcase database and the Id of the testcase as arguments (e.g., ```gradle -q coordinator --args='../testcase.db  <testcaseId>')```).

This should start both the Tensorflow and Java-Environments, which automatically connect to each other using a socket, and start running training episodes according to the code in [rl-uds-optimizer/dqn-train-eval-uds.py](./rl-uds-optimizer/dqn-train-eval-uds.py).

Logged results about training episodes will be automatically gathered in [eval-output](./eval-output).