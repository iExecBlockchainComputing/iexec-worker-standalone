# Changelog

All notable changes to this project will be documented in this file.

## [[NEXT]](https://github.com/iExecBlockchainComputing/iexec-worker-standalone/releases/tag/vNEXT) 2024

### New Features

- Setup `WorkerConfigurationService`. (#1, #3, #7)
- Setup docker-related classes. (#2, #3)
- Setup sgx-related classes. (#4, #10)
- Setup dataset-related classes. (#5, #7)
- Setup utils-related classes. (#11)
- Setup metric-related classes. (#12)
- Setup chain-related classes. (#13)
- Setup feign-related classes. (#14)
- Setup sms-related classes. (#15)
- Setup result-related classes. (#16)
- Setup compute- and tee-related classes. (#17)
- Setup pubsub-related classes. (#25)
- Setup executor-related classes. (#27)

# Bug Fixes

- Use latest released libraries and perform minimal updates to build the project. (#23)
- Repatriate latest PRs from iexec-core to stick to the v8.5.0. (#19, #20, #21, #22)
- Repatriate latest PRs from iexec-worker to stick to the v8.5.0. (#24, #25, #26)

### Quality

- Add warning in `README.md`. (#6)
- Update base code to match new Scheduler version. (#8, #9)
- Update project with latest changes from iexec-core v8.5.0 and iexec-worker v8.5.0 releases. (#18)

### Dependency Upgrade

- Upgrade to `iexec-commons-poco` 4.1.0. (#23)
- Upgrade to `iexec-common` 8.5.0. (#23)
- Upgrade to `iexec-commons-containers` 1.2.2. (#23)
- Upgrade to `iexec-blockchain-adapter-api-library` 8.5.0. (#23)
- Upgrade to `iexec-result-proxy-library` 8.5.0. (#23)
- Upgrade to `iexec-sms-library` 8.6.0. (#23)
