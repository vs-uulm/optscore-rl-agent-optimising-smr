import logging.config
import os
import sys
from ast import literal_eval as make_tuple
from datetime import datetime

import numpy as np
import tensorflow as tf
from tf_agents.agents.dqn import dqn_agent
from tf_agents.drivers import dynamic_step_driver
from tf_agents.environments import tf_py_environment
from tf_agents.metrics import py_metrics, tf_metrics
from tf_agents.networks import sequential
from tf_agents.policies import py_tf_eager_policy, random_tf_policy
from tf_agents.replay_buffers import tf_uniform_replay_buffer
from tf_agents.specs import tensor_spec
from tf_agents.train import learner
from tf_agents.train import triggers
from tf_agents.train.utils import train_utils
from tf_agents.utils import common

from UdsTfEnv import UdsTfEnv

logging.config.fileConfig('logging.conf')
logger = logging.getLogger()
logger.info(f'Tensorflow Version: {tf.version.VERSION}')

# check commandline args. We need 10 in total
if len(sys.argv) < 4:
    logger.error('Please pass required arguments (testcaseId, run#, followed by parameters as :-separated string): \n' +
                 'numIterations (int):initialCollectSteps (int):collectStepsPerIteration (int):'
                 'replayBufferMaxLength (int):batchSize (int):learningRate (String:e.g.:"1e-3"):'
                 'logInterval (int):numEvalEpisodes (int):evalInterval (int):hiddenLayers (String, e.g., "(10,)"'
                 )
    sys.exit(2)

testcase_id = sys.argv[1]
run = sys.argv[2]
logger.warning(f'TestcaseId: {testcase_id}')
args = sys.argv[3].split(":")

# root dir for output/logging
root_dir = os.path.join("../eval-output/", testcase_id, "run" + run)

# logging for Tensorboard
log_dir = os.path.join(root_dir, 'tensorboard', datetime.now().strftime("%Y%m%d-%H%M%S"))
train_summary_writer = tf.summary.create_file_writer(log_dir, flush_millis=10000)
train_summary_writer.set_as_default()

# Hyperparameters
num_iterations = int(args[0])  # @param {type:"integer"}

initial_collect_steps = int(args[1])  # @param {type:"integer"}
collect_steps_per_iteration = int(args[2])  # @param {type:"integer"}
replay_buffer_max_length = int(args[3])  # @param {type:"integer"}

batch_size = int(args[4])  # @param {type:"integer"}
learning_rate = float(args[5])  # @param {type:"number"}
log_interval = int(args[6])  # @param {type:"integer"}

num_eval_episodes = int(args[7])  # @param {type:"integer"}
eval_interval = int(args[8])  # @param {type:"integer"}

# hidden_layers example = (64, 64)
hidden_layers = make_tuple(args[9])

# log hyperparams
logger.warning(f'num_iterations: {num_iterations}, initial_collect_steps: {initial_collect_steps}, '
               f'collect_steps_per_iteration: {collect_steps_per_iteration}, '
               f'replay_buffer_max_length: {replay_buffer_max_length}, '
               f'batch_size: {batch_size}, learning_rate: {learning_rate}, log_interval: {log_interval}, '
               f'num_eval_episodes: {num_eval_episodes}, eval_interval: {eval_interval}, hidden_layers: {hidden_layers}')

# instantiate collection and evaluation environments
collect_py_env = UdsTfEnv(testcase_id, run, collect_steps_per_iteration, 4242, max_primaries=8, max_steps=2)
collect_env = tf_py_environment.TFPyEnvironment(collect_py_env)
eval_py_env = UdsTfEnv(testcase_id, run, collect_steps_per_iteration, 4343, max_primaries=8, max_steps=2)
eval_env = tf_py_environment.TFPyEnvironment(eval_py_env)

time_step_tensor_spec = tensor_spec.from_spec(collect_env.time_step_spec())
action_tensor_spec = tensor_spec.from_spec(collect_env.action_spec())

logger.info(f'time_step_tensor_spec: {time_step_tensor_spec}')
logger.info(f'action_tensor_spec: {action_tensor_spec}')

# step counter
train_step = train_utils.create_train_step()

# number of possible actions the agent can take
num_actions = np.ndarray.item(collect_py_env.action_spec().maximum) + 1


# Define a helper function to create Dense layers configured with the right
# activation and kernel initializer.
def dense_layer(num_units):
    return tf.keras.layers.Dense(
        num_units,
        activation=tf.keras.activations.relu,
        kernel_initializer=tf.keras.initializers.HeNormal(seed=1337))


# QNetwork consists of a sequence of Dense layers followed by a dense layer
# with `num_actions` units to generate one q_value per available action as
# it's output.
dense_layers = [dense_layer(num_units) for num_units in hidden_layers]
q_values_layer = tf.keras.layers.Dense(
    num_actions,
    activation=None,
    kernel_initializer=tf.keras.initializers.VarianceScaling(scale=2.0),
    bias_initializer=tf.keras.initializers.Constant(-0.02)
)

# initialize QNetwork
q_net = sequential.Sequential(dense_layers + [q_values_layer])

start_epsilon = 0.99
end_epsilon = 0.1
decaying_epsilon = tf.compat.v1.train.polynomial_decay(
    learning_rate=start_epsilon,
    global_step=train_step,
    decay_steps=num_iterations / 3,
    end_learning_rate=end_epsilon,
    power=0.9)

# initialize DQNAgent with target_net being automatically instantiated
agent = dqn_agent.DqnAgent(
    time_step_tensor_spec,
    action_tensor_spec,
    q_network=q_net,
    epsilon_greedy=decaying_epsilon,
    n_step_update=1,
    target_update_tau=0.2,
    target_update_period=80,
    optimizer=tf.keras.optimizers.Adam(learning_rate=learning_rate),
    td_errors_loss_fn=common.element_wise_huber_loss,
    gamma=0.95,
    reward_scale_factor=0.1,
    train_step_counter=train_step,
    debug_summaries=True
)

agent.initialize()

# display short summary of QNet
q_net.summary()
logger.info(f'agent.collect_data_spec: {agent.collect_data_spec}')

# set up replay buffer for training
replay_buffer = tf_uniform_replay_buffer.TFUniformReplayBuffer(
    data_spec=agent.collect_data_spec,
    batch_size=collect_env.batch_size,
    max_length=replay_buffer_max_length)

# observer for adding data to the replay buffer
replay_observer = [replay_buffer.add_batch]

dataset = replay_buffer.as_dataset(
    sample_batch_size=batch_size,
    num_steps=2
).prefetch(batch_size)
experience_dataset_fn = lambda: dataset

saved_model_dir = os.path.join(root_dir, learner.POLICY_SAVED_MODEL_DIR)
env_step_metric = py_metrics.EnvironmentSteps()

learning_triggers = [
    triggers.PolicySavedModelTrigger(
        saved_model_dir=saved_model_dir,
        agent=agent,
        train_step=train_step,
        interval=1000,
        metadata_metrics={triggers.ENV_STEP_METADATA_KEY: env_step_metric}),
    triggers.StepPerSecondLogTrigger(train_step, interval=100, log_to_terminal=True),
]

dqn_learner = learner.Learner(
    root_dir=root_dir,
    train_step=train_step,
    agent=agent,
    experience_dataset_fn=experience_dataset_fn,
    triggers=learning_triggers,
    summary_interval=5)


# function for evaluating currently learned policy
def compute_avg_return(environment, policy, num_episodes=2):
    total_return = 0.0
    for _ in range(num_episodes):

        timestep = environment.reset()
        episode_return = 0.0

        while not timestep.is_last():
            action_step = policy.action(timestep)
            timestep = environment.step(action_step.action)
            episode_return += timestep.reward
        total_return += episode_return

    avg_ret = total_return / num_episodes
    return avg_ret.numpy()[0]


# (Optional) Optimize by wrapping some code in a graph using TF function.
agent.train = common.function(agent.train)

# Evaluate the agent's policy once before training.
avg_return = compute_avg_return(eval_env, agent.policy, num_eval_episodes)
returns = [avg_return]
losses = []

# If we haven't trained yet make sure we collect some random samples first to
# fill up the Replay Buffer with some experience.
random_policy = random_tf_policy.RandomTFPolicy(time_step_spec=collect_env.time_step_spec(),
                                                action_spec=collect_env.action_spec())

env_steps = [tf_metrics.EnvironmentSteps()]
initial_collect_driver = dynamic_step_driver.DynamicStepDriver(
    env=collect_env,
    policy=random_policy,
    num_steps=initial_collect_steps,
    observers=replay_observer + env_steps
)
final_time_step, collect_policy_state = initial_collect_driver.run()

logger.info(f'Initial Collection run final step: {final_time_step}')
logger.info(f'Initial Collection run # of steps: {env_steps[0].result().numpy()}')

# Reset the environment.
time_step = collect_env.reset()

# collection metrics
train_metrics = [
    tf_metrics.NumberOfEpisodes(),
    tf_metrics.EnvironmentSteps(),
    tf_metrics.AverageReturnMetric(buffer_size=num_eval_episodes),
    tf_metrics.AverageEpisodeLengthMetric(buffer_size=num_eval_episodes),
    tf_metrics.ChosenActionHistogram()
]

# Create the actual driver to collect experience.
tf_collect_policy = agent.collect_policy
collect_policy = py_tf_eager_policy.PyTFEagerPolicy(tf_collect_policy,
                                                    use_tf_function=True)
collect_driver = dynamic_step_driver.DynamicStepDriver(
    env=collect_env,
    policy=collect_policy,
    num_steps=collect_steps_per_iteration,
    observers=replay_observer + train_metrics
)

# Initial evaluation before training begins
if eval_interval:
    avg_return = compute_avg_return(eval_env, agent.policy, num_eval_episodes)
    logger.info(f'step = {train_step}: Average Return = {avg_return}')
    returns.append(avg_return)

# Training loop
logger.info('Starting Training...')
for _ in range(num_iterations):
    # Collect a few steps and save to the replay buffer.
    time_step, _ = collect_driver.run(time_step)
    train_loss = dqn_learner.run(iterations=1)

    # log tensorboard stuff
    for train_metric in train_metrics:
        train_metric.tf_summaries(train_step=train_step)

    if log_interval and dqn_learner.train_step_numpy % log_interval == 0:
        logger.info(f'step = {train_step}: loss = {train_loss}')
        losses.append(train_loss)

    if eval_interval and dqn_learner.train_step_numpy % eval_interval == 0:
        avg_return = compute_avg_return(eval_env, agent.policy, num_eval_episodes)
        logger.info(f'step = {train_step}: Average Return = {avg_return}')
        returns.append(avg_return)


logger.warning('Finished training. Running final evaluation episodes:')
final_returns = compute_avg_return(eval_env, agent.policy, 5)
logger.warning(f'Final return is: {final_returns}')
logger.warning(f'All returns: {returns}')
logger.warning(f'All losses: {losses}')

# make sure the CSV file writers in the environments are closed
collect_py_env.close_csv_writer()
eval_py_env.close_csv_writer()
