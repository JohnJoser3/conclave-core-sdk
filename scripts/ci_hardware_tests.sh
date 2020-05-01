#!/usr/bin/env bash
set -xeuo pipefail

SCRIPT_DIR=$(dirname $(readlink -f ${BASH_SOURCE[0]}))
source ${SCRIPT_DIR}/ci_build_common.sh
source ${SCRIPT_DIR}/ci_hardware_common.sh

# Hardware tests
# Teardown any aesmd container that might be left running, build and start the aesmd container.
# The driver is expected to already be installed and loaded on the CI agent.
teardownAESM
buildAESMImage $SCRIPT_DIR/../containers/aesmd/src/docker
startAESMContainer

# Run the samples tests.
runDocker com.r3.sgx/sgxjvm-build "cd $CODE_DOCKER_DIR/samples \
    && \$GRADLE -Psgx_mode=Debug test -i ${TEST_OPTS:-}"

# Test SDK tests in hardware.
runDocker com.r3.sgx/sgxjvm-build "cd $CODE_DOCKER_DIR && ./test-sdk.sh hardware"

# Teardown AESM container
stopAndRemoveAESMImage