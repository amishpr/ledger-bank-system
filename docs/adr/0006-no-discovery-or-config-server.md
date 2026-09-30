# 0006. No service discovery or config server

Status: accepted

## Context

Spring Cloud offers Eureka for service discovery and Config Server for
centralised configuration, and many Spring microservice tutorials include
both. Each is another stateful service to run, secure and keep available.

## Decision

Use neither. Upstream addresses are plain configuration
(`LEDGER_SERVICE_URL` and friends) and configuration comes from the
environment. On Kubernetes, a Service name is already a stable DNS name with
load balancing behind it, and ConfigMaps and Secrets already deliver
configuration. Docker Compose provides the same through its network names.

## Consequences

- Fewer moving parts, and the gateway's routes are readable as plain URLs.
- Changing configuration means restarting pods, which Kubernetes does as a
  rolling update. Live refresh without a restart is not available, and is
  rarely wanted for anything but feature flags.
- Running outside a platform that provides DNS and configuration (bare VMs, for
  example) would bring back the case for a discovery service.
