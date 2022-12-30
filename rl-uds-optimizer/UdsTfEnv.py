from typing import Any

import numpy as np
from tf_agents.environments import py_environment
from tf_agents.specs import array_spec
from tf_agents.trajectories import time_step as ts

import zmq
import json
import csv
import os
import re

import logging
import logging.config


class UdsTfEnv(py_environment.PyEnvironment):
    # has to be equal to the same parameter in the Java UdsEnvironment
    _udsMaxThreads = 16
    _observation_size = _udsMaxThreads + 0
    _max_rounds = 400
    _max_primaries = 8
    _max_steps = 2
    _port = 4242

    def __init__(self, testcase_id, run, max_rounds, port, max_primaries=8, max_steps=2):
        super().__init__()

        self._max_rounds = max_rounds
        self._port = port
        self._max_primaries = max_primaries
        self._max_steps = max_steps

        # how do the actions we're getting from the agent look
        self._action_spec = array_spec.BoundedArraySpec(
            shape=(), dtype=np.int32, name='action', minimum=0, maximum=(self._max_primaries * self._max_steps)-1)

        # what do we give out as observations
        # three frames (3 *)
        # of a scalar specifying the estimated client # last round (1 +)
        # plus a scalar signfying when the last config change was attempted (1 +)
        # plus 3 lists of size of primaries, containing primaries in prev round, steps taken,
        # and rounds seen (self._max_primaries * 3)
        # plus the list of all currently known threads in the system
        self._obs_shape = 3 * (1 + 1 + self._max_primaries * 3 + self._udsMaxThreads)
        self._observation_spec = array_spec.ArraySpec(shape=(self._obs_shape,),
                                                      dtype=np.float,
                                                      name='3_prev_round_es_pr_st_rs_th')

        self._state = np.zeros(self._obs_shape, dtype=np.float)
        self._episode_ended = False
        self._round = 0

        logging.config.fileConfig('logging.conf')
        self.logger = logging.getLogger()

        # create CSV stats logging directory and files
        stats_path = f'../eval-output/{testcase_id}/run{run}'
        if not os.path.exists(stats_path):
            os.makedirs(stats_path)
        self._statsfile = open(stats_path + f'/pyenvstats-{testcase_id}-{self._port}.csv', 'w', newline='')
        self._statswriter = csv.writer(self._statsfile, delimiter=',',
                                       quotechar='"', quoting=csv.QUOTE_MINIMAL)

        self._statswriter.writerow(
            ['action',
             'reward',
             'prevRoundPrimaries',
             'prevRoundStepsTaken',
             'prevRoundRoundsSeen',
             'threadsInSystem',
             'scheduler',
             'round',
             'previousRoundDurationNs',
             'previousRoundCPUTimeUsedNs',
             'previousRoundActiveSODLClients',
             'estimatedClients',
             'roundsSinceLastConfigChange',
             'confprim',
             'confsteps',
             'confmaxthreads',
             'confdeterministic'])

        context = zmq.Context()
        self.logger.info("Connecting to UdsEnvironment server…")
        self.socket = context.socket(zmq.REQ)
        self.socket.connect("tcp://localhost:" + str(self._port))
        self.logger.info(f'Socket connected ({self._port})')

    def action_spec(self):
        return self._action_spec

    def observation_spec(self):
        return self._observation_spec

    def _reset(self):
        self.logger.info('Sending reset ...')
        self.socket.send(b'reset')
        # receive an answer, necessary so we keep the req-rep pattern of øMQ REQ sockets
        reply = self.socket.recv_string()
        self.logger.debug('Reset answer: %s', reply)

        self._state = np.zeros(self._obs_shape, dtype=np.float)
        self._episode_ended = False
        self._round = 0

        return ts.restart(self._state)

    def _step(self, action):
        # Make sure episodes don't go on forever.
        max_action = np.ndarray.item(self._action_spec.maximum)
        if action < 0 or action > max_action:
            raise ValueError(f'`action` should be between 0 and {max_action} (=> 1 and {max_action + 1})')

        if self._episode_ended:
            self.logger.info('End of episode reached, resetting environment ...')
            return self.reset()

        action = action + 1
        self.logger.debug('Sending action: %s', action)
        self.socket.send(str(action).encode())

        msg = self.socket.recv_string()
        msg_dict = json.loads(msg)
        self.logger.debug('Received answer: %s', msg_dict)

        # ['action', 'reward', 'prevRoundPrimaries', 'prevRoundStepsTaken', 'prevRoundRoundsSeen', 'threadsInSystem',
        # 'scheduler',
        # 'round',
        # 'scheduledlastround',
        # 'confprim',
        # 'confsteps',
        # 'confmaxthreads', 'confdeterministic']

        # {'reward': 8, 'observation': {'scheduler': 'UDScheduler #0', 'previousRoundPrimaries': [0.8, 0.8, 0.8, 0.8,
        # 0.8, 0.8, 0.8], 'previousRoundStepsTaken': [2, 1, 1, 1, 1, 1, 1], 'previousRoundRoundsSeen': [2, 1, 1, 1,
        # 1, 1, 1], 'threadsInSystem': [0.8, 0.8, 0.8, 0.8, 0.8, 0.8, 0.8], 'round': 2,
        # 'previousRoundDurationNs': 4587397, 'previousRoundCPUTimeUsedNs': 10599423, previousRoundActiveSODLClients: 4,
        # 'currentConfig': {'primaryNumber': 7, 'steps': 1, 'maxThreads': 16, 'deterministic': True}}}
        row = [
            action, msg_dict['reward'],
            msg_dict['observation'][0]['previousRoundPrimaries'],
            msg_dict['observation'][0]['previousRoundStepsTaken'],
            msg_dict['observation'][0]['previousRoundRoundsSeen'],
            msg_dict['observation'][0]['threadsInSystem'],
            re.match(r"UDScheduler #(\d+)", msg_dict['observation'][0]['scheduler']).group(1),
            msg_dict['observation'][0]['round'],
            msg_dict['observation'][0]['previousRoundDurationNs'],
            msg_dict['observation'][0]['previousRoundCPUTimeUsedNs'],
            msg_dict['observation'][0]['previousRoundActiveSODLClients'],
            msg_dict['observation'][0]['estimatedClients'],
            msg_dict['observation'][0]['roundsSinceLastConfigChange'],
            msg_dict['observation'][0]['currentConfig']['primaryNumber'],
            msg_dict['observation'][0]['currentConfig']['steps'],
            msg_dict['observation'][0]['currentConfig']['maxThreads'],
            msg_dict['observation'][0]['currentConfig']['deterministic']
        ]

        self._statswriter.writerow(row)
        self._statsfile.flush()

        # increment round number
        self._round += 1

        prev_r1_es = np.array(msg_dict['observation'][0]['estimatedClients'], dtype=np.float)
        prev_r1_lc = np.array(msg_dict['observation'][0]['roundsSinceLastConfigChange'], dtype=np.float)
        prev_r1_pr = np.array(msg_dict['observation'][0]['previousRoundPrimaries'], dtype=np.float)
        prev_r1_st = np.array(msg_dict['observation'][0]['previousRoundStepsTaken'], dtype=np.float)
        prev_r1_rs = np.array(msg_dict['observation'][0]['previousRoundRoundsSeen'], dtype=np.float)
        prev_r1_th = np.array(msg_dict['observation'][0]['threadsInSystem'], dtype=np.float)

        prev_r1_pr_padded = np.pad(prev_r1_pr, (0, self._max_primaries - prev_r1_pr.size), constant_values=(0, 0))
        prev_r1_st_padded = np.pad(prev_r1_st, (0, self._max_primaries - prev_r1_st.size), constant_values=(0, 0))
        prev_r1_rs_padded = np.pad(prev_r1_rs, (0, self._max_primaries - prev_r1_rs.size), constant_values=(0, 0))
        prev_r1_th_padded = np.pad(prev_r1_th, (0, self._udsMaxThreads - prev_r1_th.size), constant_values=(0, 0))

        prev_r2_es = np.array(msg_dict['observation'][1]['estimatedClients'], dtype=np.float)
        prev_r2_lc = np.array(msg_dict['observation'][1]['roundsSinceLastConfigChange'], dtype=np.float)
        prev_r2_pr = np.array(msg_dict['observation'][1]['previousRoundPrimaries'], dtype=np.float)
        prev_r2_st = np.array(msg_dict['observation'][1]['previousRoundStepsTaken'], dtype=np.float)
        prev_r2_rs = np.array(msg_dict['observation'][1]['previousRoundRoundsSeen'], dtype=np.float)
        prev_r2_th = np.array(msg_dict['observation'][1]['threadsInSystem'], dtype=np.float)

        prev_r2_pr_padded = np.pad(prev_r2_pr, (0, self._max_primaries - prev_r2_pr.size), constant_values=(0, 0))
        prev_r2_st_padded = np.pad(prev_r2_st, (0, self._max_primaries - prev_r2_st.size), constant_values=(0, 0))
        prev_r2_rs_padded = np.pad(prev_r2_rs, (0, self._max_primaries - prev_r2_rs.size), constant_values=(0, 0))
        prev_r2_th_padded = np.pad(prev_r2_th, (0, self._udsMaxThreads - prev_r2_th.size), constant_values=(0, 0))

        prev_r3_es = np.array(msg_dict['observation'][2]['estimatedClients'], dtype=np.float)
        prev_r3_lc = np.array(msg_dict['observation'][2]['roundsSinceLastConfigChange'], dtype=np.float)
        prev_r3_pr = np.array(msg_dict['observation'][2]['previousRoundPrimaries'], dtype=np.float)
        prev_r3_st = np.array(msg_dict['observation'][2]['previousRoundStepsTaken'], dtype=np.float)
        prev_r3_rs = np.array(msg_dict['observation'][2]['previousRoundRoundsSeen'], dtype=np.float)
        prev_r3_th = np.array(msg_dict['observation'][2]['threadsInSystem'], dtype=np.float)

        prev_r3_pr_padded = np.pad(prev_r3_pr, (0, self._max_primaries - prev_r3_pr.size), constant_values=(0, 0))
        prev_r3_st_padded = np.pad(prev_r3_st, (0, self._max_primaries - prev_r3_st.size), constant_values=(0, 0))
        prev_r3_rs_padded = np.pad(prev_r3_rs, (0, self._max_primaries - prev_r3_rs.size), constant_values=(0, 0))
        prev_r3_th_padded = np.pad(prev_r3_th, (0, self._udsMaxThreads - prev_r3_th.size), constant_values=(0, 0))

        obs = np.concatenate([[prev_r1_es], [prev_r1_lc],
                              prev_r1_pr_padded, prev_r1_st_padded, prev_r1_rs_padded, prev_r1_th_padded,
                              [prev_r2_es], [prev_r2_lc],
                              prev_r2_pr_padded, prev_r2_st_padded, prev_r2_rs_padded, prev_r2_th_padded,
                              [prev_r3_es], [prev_r3_lc],
                              prev_r3_pr_padded, prev_r3_st_padded, prev_r3_rs_padded, prev_r3_th_padded])

        if self._round < self._max_rounds:
            self.logger.debug(f'UdsTfEnv ({self._port}): Step finished ... returning ts.transition')
            return ts.transition(obs, reward=np.int(msg_dict['reward']))
        else:
            self.logger.debug(f'UdsTfEnv ({self._port}): Episode finished ... returning ts.termination')
            self._episode_ended = True
            return ts.termination(obs, reward=np.int(msg_dict['reward']))

    def get_info(self) -> Any:
        pass

    def get_state(self) -> Any:
        return self._state

    def set_state(self, state: Any) -> None:
        self._state = state

    def close_csv_writer(self) -> None:
        self._statsfile.close()
