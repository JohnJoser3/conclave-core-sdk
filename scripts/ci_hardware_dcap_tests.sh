#!/usr/bin/env bash
set -xeuo pipefail

script_dir=$(dirname ${BASH_SOURCE[0]})
source ${script_dir}/ci_build_common.sh
source ${script_dir}/ci_hardware_common.sh

# Teardown any aesmd container that might be left running, build and start the aesmd container.
# The driver is expected to already be installed and loaded on the CI agent.
teardownAESM
startAESMContainer

# Run the DCAP attestation tests in Debug and Simulation modes using integration tests.
# Note, the DCAP run doesn't test the samples as they can be run on the EPID machines which are cheaper.
runDocker $container_image_conclave_build "cd integration-tests \
    && ./gradlew -PenclaveMode=debug general:tests:test --tests com.r3.conclave.integrationtests.general.tests.AttestationTests"

# Teardown AESM container
teardownAESM
